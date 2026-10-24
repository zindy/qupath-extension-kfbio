package qupath.ext.kfbio;

import org.junit.jupiter.api.Test;
import qupath.lib.common.ColorTools;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.regions.RegionRequest;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests on synthetic files. These check that the code does what it was designed to do;
 * they cannot show that the format was understood correctly (see {@link SyntheticKfb}).
 */
class KfbFormatTest {

	private static final int TOLERANCE = 12;

	private static void assertColor(int expectedRgb, int actualRgb, String message) {
		for (int shift : new int[]{16, 8, 0}) {
			int e = (expectedRgb >> shift) & 0xFF;
			int a = (actualRgb >> shift) & 0xFF;
			assertTrue(Math.abs(e - a) <= TOLERANCE,
					message + ": expected " + Integer.toHexString(expectedRgb) + " but was " + Integer.toHexString(actualRgb & 0xFFFFFF));
		}
	}

	private static BufferedImage read(ImageServer<BufferedImage> server, double downsample) throws IOException {
		return server.readRegion(RegionRequest.createInstance(server.getPath(), downsample, 0, 0, server.getWidth(), server.getHeight()));
	}

	private static int rgb(BufferedImage img, int x, int y) {
		return img.getRGB(x, y) & 0xFFFFFF;
	}

	private static KfbImageServer open(Path path) throws IOException {
		return new KfbImageServer(path.toUri());
	}

	@Test
	void parsesBrightfieldHeaderAndPyramid() throws Exception {
		Path path = SyntheticKfb.tempFile(".kfb");
		int n = SyntheticKfb.write(path, new SyntheticKfb.Spec());
		assertEquals(9, n);
		try (var kfb = KfbFile.open(path)) {
			var h = kfb.header();
			assertEquals(KfbFile.Format.BRIGHTFIELD, h.format());
			assertEquals(9, h.tileCount());
			assertEquals(600, h.baseWidth());
			assertEquals(450, h.baseHeight());
			assertEquals(40, h.scanScale());
			assertEquals(0.25, h.mpp(), 1e-9);
			assertEquals(256, h.tileSize());
			assertEquals(3, h.channelCount());
			assertTrue(h.channels().isEmpty());

			var pyramid = kfb.pyramid();
			assertEquals(256, pyramid.tileSize());
			assertEquals(3, pyramid.levels().size());
			int[][] expected = {{600, 450, 6}, {300, 225, 2}, {150, 113, 1}};
			for (int i = 0; i < 3; i++) {
				var level = pyramid.levels().get(i);
				assertEquals(i, level.zoomLevel());
				assertEquals(expected[i][0], level.width());
				assertEquals(expected[i][1], level.height());
				assertEquals(expected[i][2], level.tiles().size());
			}
		}
	}

	@Test
	void findsTilesOverlappingARegion() throws Exception {
		Path path = SyntheticKfb.tempFile(".kfb");
		SyntheticKfb.write(path, new SyntheticKfb.Spec());
		try (var kfb = KfbFile.open(path)) {
			var level0 = kfb.pyramid().levels().get(0);
			var corner = level0.tilesIntersecting(250, 250, 10, 10);
			assertEquals(4, corner.size());
			for (int i = 1; i < corner.size(); i++)
				assertTrue(corner.get(i - 1).ordinal() < corner.get(i).ordinal(), "tiles should be in file order");
			assertEquals(1, level0.tilesIntersecting(0, 0, 256, 256).size());
			assertEquals(0, level0.tilesIntersecting(600, 0, 10, 10).size());
			assertEquals(2, kfb.pyramid().levels().get(1).tilesIntersecting(250, 0, 100, 225).size());
		}
	}

	@Test
	void findsAssociatedImages() throws Exception {
		Path path = SyntheticKfb.tempFile(".kfb");
		SyntheticKfb.write(path, new SyntheticKfb.Spec());
		try (var kfb = KfbFile.open(path)) {
			var images = kfb.associatedImages();
			assertEquals(Set.of(KfbFile.AssociatedKind.THUMBNAIL, KfbFile.AssociatedKind.LABEL, KfbFile.AssociatedKind.MACRO),
					images.stream().map(KfbFile.AssociatedImage::kind).collect(Collectors.toSet()));
			for (var image : images) {
				int expected = switch (image.kind()) {
					case THUMBNAIL -> SyntheticKfb.THUMBNAIL_RGB;
					case LABEL -> SyntheticKfb.LABEL_RGB;
					case MACRO -> SyntheticKfb.MACRO_RGB;
				};
				var decoded = KfbJpeg.decodeRgb(kfb.readAssociatedBytes(image));
				assertColor(expected, decoded.rgb()[decoded.rgb().length / 2], image.kind().imageName());
			}
		}
	}

	@Test
	void recoversAssociatedImageWithWrongDeclaredLength() throws Exception {
		Path path = SyntheticKfb.tempFile(".kfb");
		var spec = new SyntheticKfb.Spec();
		spec.corruptMacroLength = true;
		SyntheticKfb.write(path, spec);
		try (var kfb = KfbFile.open(path)) {
			var macro = kfb.associatedImages().stream()
					.filter(a -> a.kind() == KfbFile.AssociatedKind.MACRO).findFirst().orElseThrow();
			assertTrue(macro.dataLength() < 999_999);
			var decoded = KfbJpeg.decodeRgb(kfb.readAssociatedBytes(macro));
			assertEquals(80, decoded.width());
			assertColor(SyntheticKfb.MACRO_RGB, decoded.rgb()[0], "macro");
		}
	}

	@Test
	void serverServesBrightfieldPyramid() throws Exception {
		Path path = SyntheticKfb.tempFile(".kfb");
		SyntheticKfb.write(path, new SyntheticKfb.Spec());
		try (var server = open(path)) {
			assertEquals(600, server.getWidth());
			assertEquals(450, server.getHeight());
			assertTrue(server.isRGB());
			assertEquals(3, server.nChannels());
			assertEquals(3, server.nResolutions());
			assertEquals(1.0, server.getDownsampleForResolution(0), 1e-9);
			assertEquals(2.0, server.getDownsampleForResolution(1), 1e-9);
			assertEquals(4.0, server.getDownsampleForResolution(2), 1e-9);
			assertEquals(0.25, server.getPixelCalibration().getAveragedPixelSizeMicrons(), 1e-9);
			assertEquals(40, server.getMetadata().getMagnification(), 1e-9);
			assertEquals(256, server.getMetadata().getPreferredTileWidth());

			var full = read(server, 1.0);
			assertEquals(600, full.getWidth());
			// Centre of each tile, and either side of a tile boundary
			assertColor(SyntheticKfb.brightfieldColor(0, 0, 0), rgb(full, 100, 100), "tile 0,0");
			assertColor(SyntheticKfb.brightfieldColor(0, 0, 0), rgb(full, 255, 100), "left of boundary");
			assertColor(SyntheticKfb.brightfieldColor(0, 1, 0), rgb(full, 256, 100), "right of boundary");
			assertColor(SyntheticKfb.brightfieldColor(0, 2, 1), rgb(full, 590, 440), "last tile");
			assertColor(SyntheticKfb.brightfieldColor(0, 1, 1), rgb(full, 300, 300), "tile 1,1");

			var level1 = read(server, 2.0);
			assertEquals(300, level1.getWidth());
			assertColor(SyntheticKfb.brightfieldColor(1, 0, 0), rgb(level1, 100, 100), "level 1 tile 0,0");
			assertColor(SyntheticKfb.brightfieldColor(1, 1, 0), rgb(level1, 280, 100), "level 1 tile 1,0");

			var level2 = read(server, 4.0);
			assertColor(SyntheticKfb.brightfieldColor(2, 0, 0), rgb(level2, 75, 50), "level 2");

			// A region that straddles four tiles
			var straddle = server.readRegion(RegionRequest.createInstance(server.getPath(), 1.0, 240, 240, 32, 32));
			assertColor(SyntheticKfb.brightfieldColor(0, 0, 0), rgb(straddle, 5, 5), "straddle tl");
			assertColor(SyntheticKfb.brightfieldColor(0, 1, 0), rgb(straddle, 25, 5), "straddle tr");
			assertColor(SyntheticKfb.brightfieldColor(0, 0, 1), rgb(straddle, 5, 25), "straddle bl");
			assertColor(SyntheticKfb.brightfieldColor(0, 1, 1), rgb(straddle, 25, 25), "straddle br");

			assertEquals(Set.of("thumbnail", "label", "macro"), Set.copyOf(server.getAssociatedImageList()));
			assertNotNull(server.getAssociatedImage("label"));
			assertThrows(IllegalArgumentException.class, () -> server.getAssociatedImage("nonsense"));
		}
	}

	@Test
	void handlesTileDataBeforeTheIndex() throws Exception {
		Path path = SyntheticKfb.tempFile(".kfb");
		var spec = new SyntheticKfb.Spec();
		spec.payloadBeforeIndex = true;
		SyntheticKfb.write(path, spec);
		try (var server = open(path)) {
			var full = read(server, 1.0);
			assertColor(SyntheticKfb.brightfieldColor(0, 1, 0), rgb(full, 500, 100), "tile 1,0 (right edge)");
			assertColor(SyntheticKfb.brightfieldColor(0, 2, 0), rgb(full, 550, 100), "tile 2,0");
			assertEquals(3, server.getAssociatedImageList().size());
		}
	}

	@Test
	void missingTilesAreWhite() throws Exception {
		Path path = SyntheticKfb.tempFile(".kfb");
		var spec = new SyntheticKfb.Spec();
		spec.omitTiles.add("0:1:1");
		SyntheticKfb.write(path, spec);
		try (var server = open(path)) {
			var full = read(server, 1.0);
			assertEquals(0xFFFFFF, rgb(full, 300, 300));
			assertColor(SyntheticKfb.brightfieldColor(0, 1, 0), rgb(full, 300, 100), "neighbour");
		}
	}

	@Test
	void levelsWithoutTilesAreSkipped() throws Exception {
		Path path = SyntheticKfb.tempFile(".kfb");
		var spec = new SyntheticKfb.Spec();
		spec.omitLevels.add(1);
		SyntheticKfb.write(path, spec);
		try (var server = open(path)) {
			assertEquals(2, server.nResolutions());
			assertEquals(4.0, server.getDownsampleForResolution(1), 1e-9);
			var level2 = read(server, 4.0);
			assertColor(SyntheticKfb.brightfieldColor(2, 0, 0), rgb(level2, 75, 50), "level 2");
		}
	}

	private static SyntheticKfb.Spec fluorescenceSpec() {
		var spec = new SyntheticKfb.Spec();
		spec.fluorescence = true;
		spec.width = 300;
		spec.height = 200;
		spec.tileSize = 128;
		spec.scanScale = 20;
		return spec;
	}

	@Test
	void parsesFluorescenceChannelMetadata() throws Exception {
		Path path = SyntheticKfb.tempFile(".kfbf");
		SyntheticKfb.write(path, fluorescenceSpec());
		try (var kfb = KfbFile.open(path)) {
			var h = kfb.header();
			assertEquals(KfbFile.Format.FLUORESCENCE, h.format());
			assertEquals(9, h.tileCount());
			assertEquals(2, h.channelCount());
			assertEquals(List.of("DAPI", "FITC"), h.channels().stream().map(KfbFile.ChannelInfo::name).toList());
			assertEquals(0x0000FF, h.channels().get(0).rgb());
			assertEquals(0x00FF00, h.channels().get(1).rgb());
			assertEquals(12.5, h.channels().get(0).exposureMs(), 1e-9);
			assertEquals(30.0, h.channels().get(1).exposureMs(), 1e-9);
			assertEquals(18, kfb.tiles().size());
			assertEquals(3, kfb.pyramid().levels().size());
		}
	}

	@Test
	void serverServesFluorescence() throws Exception {
		Path path = SyntheticKfb.tempFile(".kfbf");
		SyntheticKfb.write(path, fluorescenceSpec());
		try (var server = open(path)) {
			assertFalse(server.isRGB());
			assertEquals(2, server.nChannels());
			assertEquals("DAPI", server.getChannel(0).getName());
			assertEquals("FITC", server.getChannel(1).getName());
			assertEquals(ColorTools.packRGB(0, 0, 255), server.getChannel(0).getColor());
			assertEquals(ColorTools.packRGB(0, 255, 0), server.getChannel(1).getColor());
			assertEquals(20, server.getMetadata().getMagnification(), 1e-9);

			var img = read(server, 1.0);
			assertEquals(2, img.getRaster().getNumBands());
			int[][] samples = {{64, 64, 0, 0}, {200, 64, 1, 0}, {280, 150, 2, 1}};
			for (var s : samples) {
				for (int c = 0; c < 2; c++) {
					int expected = SyntheticKfb.channelValue(c, 0, s[2], s[3]);
					int actual = img.getRaster().getSample(s[0], s[1], c);
					assertTrue(Math.abs(expected - actual) <= 3,
							"channel " + c + " at " + s[0] + "," + s[1] + ": expected " + expected + " but was " + actual);
				}
			}
			var level1 = read(server, 2.0);
			assertEquals(SyntheticKfb.channelValue(0, 1, 0, 0), level1.getRaster().getSample(50, 50, 0), 3);
		}
	}

	@Test
	void fluorescenceWithoutChannelMetadataFallsBackToOneChannel() throws Exception {
		Path path = SyntheticKfb.tempFile(".kfbf");
		var spec = fluorescenceSpec();
		spec.channelMetadata = false;
		SyntheticKfb.write(path, spec);
		try (var server = open(path)) {
			assertEquals(1, server.nChannels());
			assertEquals("Channel 1", server.getChannel(0).getName());
		}
	}

	@Test
	void rejectsFilesThatAreNotKfb() throws Exception {
		Path path = SyntheticKfb.tempFile(".kfb");
		Files.write(path, new byte[2048]);
		assertThrows(IOException.class, () -> KfbFile.open(path).close());
		assertFalse(KfbFile.hasKfbMagic(path));

		// Valid magic but a tile index that cannot fit in the file
		Path valid = SyntheticKfb.tempFile(".kfb");
		SyntheticKfb.write(valid, new SyntheticKfb.Spec());
		byte[] bytes = Files.readAllBytes(valid);
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0x10, 1_000_000);
		Files.write(valid, bytes);
		assertTrue(KfbFile.hasKfbMagic(valid));
		assertThrows(IOException.class, () -> KfbFile.open(valid).close());
	}

	@Test
	void builderChecksExtensionAndMagic() throws Exception {
		var builder = new KfbImageServerBuilder();
		Path good = SyntheticKfb.tempFile(".kfb");
		SyntheticKfb.write(good, new SyntheticKfb.Spec());
		assertEquals(4f, builder.checkImageSupport(good.toUri()).getSupportLevel());

		Path wrongExtension = SyntheticKfb.tempFile(".tif");
		Files.copy(good, wrongExtension, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		assertEquals(0f, builder.checkImageSupport(wrongExtension.toUri()).getSupportLevel());

		Path junk = SyntheticKfb.tempFile(".kfb");
		Files.write(junk, new byte[100]);
		assertEquals(0f, builder.checkImageSupport(junk.toUri()).getSupportLevel());

		Path magicOnly = SyntheticKfb.tempFile(".kfb");
		Files.write(magicOnly, new byte[]{(byte) 0xF1, 0x01, (byte) 0xEE, (byte) 0xEE, 0, 0, 0, 0});
		assertNull(builder.buildServer(magicOnly.toUri()));

		assertTrue(builder.matchClassName("kfbio"));
		assertFalse(builder.matchClassName("openslide"));
	}

	@Test
	void quPathDiscoversTheBuilder() throws Exception {
		boolean registered = ServiceLoader.load(ImageServerBuilder.class).stream()
				.anyMatch(p -> p.type() == KfbImageServerBuilder.class);
		assertTrue(registered, "META-INF/services entry for ImageServerBuilder is missing");

		Path path = SyntheticKfb.tempFile(".kfb");
		SyntheticKfb.write(path, new SyntheticKfb.Spec());
		try (var server = ImageServers.buildServer(path.toUri())) {
			assertEquals(KfbImageServer.class, server.getClass());
			assertEquals("KFBio", server.getServerType());
			// The server must be rebuildable from its builder (this is what projects rely on)
			try (var rebuilt = server.getBuilder().build()) {
				assertEquals(600, rebuilt.getWidth());
			}
		}
	}
}
