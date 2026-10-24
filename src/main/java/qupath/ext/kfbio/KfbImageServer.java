package qupath.ext.kfbio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.ColorTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.ImageServerMetadata.ImageResolutionLevel;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.images.servers.TileRequest;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * ImageServer for KFBio whole slide images ({@code .kfb} brightfield and {@code .kfbf} fluorescence).
 * <p>
 * The file format is parsed by {@link KfbFile}; this class maps it onto QuPath's tiled-server model.
 * <ul>
 *     <li>Pyramid levels are those that contain tiles (plus level 0), with exact power-of-two downsamples.</li>
 *     <li>QuPath tiles are assembled from whichever file tiles overlap them, so the file's tiles do not have
 *     to line up with QuPath's grid. Areas without tiles are white (brightfield) or zero (fluorescence).</li>
 *     <li>Fluorescence files are served as 8-bit multichannel images, one grayscale JPEG per channel.</li>
 * </ul>
 */
public class KfbImageServer extends AbstractTileableImageServer {

	private static final Logger logger = LoggerFactory.getLogger(KfbImageServer.class);

	/** Default colours for fluorescence files that don't record any. */
	private static final int[] FLUORESCENCE_PALETTE = {
			0x0000FF, 0x00FF00, 0xFF0000, 0xFFFF00, 0xFF00FF, 0x00FFFF
	};

	private final URI uri;
	private final String[] args;
	private final KfbFile kfb;
	private final ImageServerMetadata originalMetadata;
	/** Maps the index of a QuPath resolution level to the KFB zoom level that provides it. */
	private final int[] zoomForLevel;
	private final boolean fluorescence;
	private final int nChannels;

	/**
	 * Create a server for a KFB or KFBF file.
	 * @param uri URI of a local file
	 * @param args optional arguments (currently unused)
	 * @throws IOException if the file cannot be opened or parsed
	 */
	public KfbImageServer(URI uri, String... args) throws IOException {
		super();
		this.uri = uri;
		this.args = args;

		Path path = GeneralTools.toPath(uri);
		if (path == null || !Files.isRegularFile(path))
			throw new IOException("KFB images must be local files, but got " + uri);

		this.kfb = KfbFile.open(path);
		try {
			var header = kfb.header();
			var pyramid = kfb.pyramid();
			this.fluorescence = header.format() == KfbFile.Format.FLUORESCENCE;
			this.nChannels = header.channelCount();

			// Only keep levels that have tiles - with the exception of level 0, which defines the image
			var kfbLevels = new ArrayList<KfbFile.Level>();
			for (var level : pyramid.levels()) {
				if (level.zoomLevel() == 0 || !level.tiles().isEmpty())
					kfbLevels.add(level);
			}
			var base = kfbLevels.get(0);
			if (base.tiles().isEmpty())
				throw new IOException("No full-resolution tiles found in " + path);

			zoomForLevel = new int[kfbLevels.size()];
			var resolutionBuilder = new ImageResolutionLevel.Builder(base.width(), base.height());
			for (int i = 0; i < kfbLevels.size(); i++) {
				var level = kfbLevels.get(i);
				zoomForLevel[i] = level.zoomLevel();
				resolutionBuilder.addLevel(1L << level.zoomLevel(), level.width(), level.height());
			}

			double mpp = header.mpp();
			if (!(mpp > 0) || Double.isInfinite(mpp)) {
				logger.warn("Invalid pixel size {} in {}, pixel size will be unknown", mpp, path.getFileName());
				mpp = Double.NaN;
			}

			var builder = new ImageServerMetadata.Builder(getClass(), uri.toString(), base.width(), base.height())
					.name(path.getFileName().toString())
					.pixelType(PixelType.UINT8)
					.preferredTileSize(pyramid.tileSize(), pyramid.tileSize())
					.pixelSizeMicrons(mpp, mpp)
					.levels(resolutionBuilder.build());
			if (header.scanScale() > 0)
				builder.magnification(header.scanScale());
			if (fluorescence)
				builder.rgb(false).channels(createChannels(header));
			else
				builder.rgb(true).channels(ImageChannel.getDefaultRGBChannels());
			this.originalMetadata = builder.build();
		} catch (IOException | RuntimeException e) {
			try {
				kfb.close();
			} catch (IOException suppressed) {
				e.addSuppressed(suppressed);
			}
			throw e;
		}
	}

	private static List<ImageChannel> createChannels(KfbFile.Header header) {
		var channels = new ArrayList<ImageChannel>();
		for (int c = 0; c < header.channelCount(); c++) {
			String name;
			int rgb;
			if (c < header.channels().size()) {
				var info = header.channels().get(c);
				name = info.name().isBlank() ? "Channel " + (c + 1) : info.name();
				rgb = info.rgb();
			} else {
				name = "Channel " + (c + 1);
				rgb = FLUORESCENCE_PALETTE[c % FLUORESCENCE_PALETTE.length];
			}
			channels.add(ImageChannel.getInstance(name, ColorTools.packRGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)));
		}
		return channels;
	}

	@Override
	protected BufferedImage readTile(TileRequest tileRequest) throws IOException {
		var level = kfb.pyramid().levels().get(zoomForLevel[tileRequest.getLevel()]);
		int x = tileRequest.getTileX();
		int y = tileRequest.getTileY();
		int w = tileRequest.getTileWidth();
		int h = tileRequest.getTileHeight();
		var tiles = level.tilesIntersecting(x, y, w, h);
		return fluorescence ? readFluorescenceTile(tiles, x, y, w, h) : readBrightfieldTile(tiles, x, y, w, h);
	}

	private BufferedImage readBrightfieldTile(List<KfbFile.Tile> tiles, int x, int y, int w, int h) throws IOException {
		var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		var data = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
		Arrays.fill(data, 0xFFFFFF);
		for (var tile : tiles) {
			var decoded = KfbJpeg.decodeRgb(kfb.readTileBytes(tile));
			// The JPEG may be padded beyond the size in the tile record; only the recorded area counts
			int srcW = Math.min(decoded.width(), tile.width());
			int srcH = Math.min(decoded.height(), tile.height());
			int dx = tile.posX() - x;
			int dy = tile.posY() - y;
			int x0 = Math.max(0, -dx);
			int y0 = Math.max(0, -dy);
			int x1 = Math.min(srcW, w - dx);
			int y1 = Math.min(srcH, h - dy);
			if (x1 <= x0)
				continue;
			for (int sy = y0; sy < y1; sy++)
				System.arraycopy(decoded.rgb(), sy * decoded.width() + x0, data, (sy + dy) * w + dx + x0, x1 - x0);
		}
		return img;
	}

	private BufferedImage readFluorescenceTile(List<KfbFile.Tile> tiles, int x, int y, int w, int h) throws IOException {
		var colorModel = getDefaultColorModel();
		var raster = colorModel.createCompatibleWritableRaster(w, h);
		for (var tile : tiles) {
			if (tile.channel() < 0 || tile.channel() >= nChannels)
				continue;
			var decoded = KfbJpeg.decodeLuma(kfb.readTileBytes(tile));
			int srcW = Math.min(decoded.width(), tile.width());
			int srcH = Math.min(decoded.height(), tile.height());
			int dx = tile.posX() - x;
			int dy = tile.posY() - y;
			int x0 = Math.max(0, -dx);
			int y0 = Math.max(0, -dy);
			int x1 = Math.min(srcW, w - dx);
			int y1 = Math.min(srcH, h - dy);
			if (x1 <= x0 || y1 <= y0)
				continue;
			int cw = x1 - x0;
			int ch = y1 - y0;
			var samples = new int[cw * ch];
			for (int sy = 0; sy < ch; sy++) {
				for (int sx = 0; sx < cw; sx++)
					samples[sy * cw + sx] = decoded.pixels()[(y0 + sy) * decoded.width() + x0 + sx] & 0xFF;
			}
			raster.setSamples(dx + x0, dy + y0, cw, ch, tile.channel(), samples);
		}
		return new BufferedImage(colorModel, raster, false, null);
	}

	@Override
	public Collection<URI> getURIs() {
		return Collections.singletonList(uri);
	}

	@Override
	protected String createID() {
		return ServerTools.createDefaultID(getClass(), uri, args);
	}

	@Override
	public String getServerType() {
		return "KFBio";
	}

	@Override
	public ImageServerMetadata getOriginalMetadata() {
		return originalMetadata;
	}

	@Override
	protected ServerBuilder<BufferedImage> createServerBuilder() {
		return DefaultImageServerBuilder.createInstance(KfbImageServerBuilder.class, getMetadata(), uri, args);
	}

	@Override
	public List<String> getAssociatedImageList() {
		return kfb.associatedImages().stream().map(a -> a.kind().imageName()).toList();
	}

	@Override
	public BufferedImage getAssociatedImage(String name) {
		for (var image : kfb.associatedImages()) {
			if (image.kind().imageName().equals(name)) {
				try {
					var decoded = KfbJpeg.decodeRgb(kfb.readAssociatedBytes(image));
					var img = new BufferedImage(decoded.width(), decoded.height(), BufferedImage.TYPE_INT_RGB);
					img.setRGB(0, 0, decoded.width(), decoded.height(), decoded.rgb(), 0, decoded.width());
					return img;
				} catch (IOException e) {
					logger.error("Unable to read associated image '{}' from {}: {}", name, uri, e.getMessage(), e);
					break;
				}
			}
		}
		throw new IllegalArgumentException("Unable to find associated image with the name " + name);
	}

	/**
	 * Get the parsed file header, e.g. for scripting or diagnostics.
	 * @return the header
	 */
	public KfbFile.Header getKfbHeader() {
		return kfb.header();
	}

	@Override
	public void close() throws IOException {
		kfb.close();
	}
}
