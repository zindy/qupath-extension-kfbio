package qupath.ext.kfbio;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes small synthetic KFB and KFBF files.
 * <p>
 * <b>This is not evidence that real files can be read.</b> The layout written here is the layout as understood by
 * kfb2zarr (and its test helpers), which is exactly what {@link KfbFile} implements. The tests built on this
 * therefore check that the code does what it was meant to do, not that the format was understood correctly.
 */
final class SyntheticKfb {

	static final int HEADER_SIZE = 0x400;

	/** What to write. */
	static final class Spec {
		int width = 600;
		int height = 450;
		int tileSize = 256;
		int scanScale = 40;
		float mpp = 0.25f;
		boolean fluorescence = false;
		/** Brightfield only: put tile data before the tile index (so that offsets relative to the index are negative). */
		boolean payloadBeforeIndex = false;
		/** Write a thumbnail, label and macro. */
		boolean associated = true;
		/** Write a bogus length for the macro image, to exercise the end-of-JPEG search. */
		boolean corruptMacroLength = false;
		/** Zoom levels to leave out entirely. */
		final Set<Integer> omitLevels = new HashSet<>();
		/** Tiles to leave out, as {@code "level:gx:gy"}. */
		final Set<String> omitTiles = new HashSet<>();
		/** Fluorescence only. */
		List<String> channelNames = List.of("DAPI", "FITC");
		int[][] channelColors = {{0, 0, 255}, {0, 255, 0}};
		double[] exposures = {12.5, 30.0};
		/** Fluorescence only: write the channel metadata block. */
		boolean channelMetadata = true;
	}

	static final int THUMBNAIL_RGB = 0xC03020;
	static final int LABEL_RGB = 0x20C030;
	static final int MACRO_RGB = 0x2030C0;

	/** The colour written to the brightfield tile at a grid position of a level. */
	static int brightfieldColor(int level, int gx, int gy) {
		int r = (60 + 70 * gx) & 0xFF;
		int g = (60 + 70 * gy) & 0xFF;
		int b = (90 + 50 * level) & 0xFF;
		return (r << 16) | (g << 8) | b;
	}

	/** The value written to a fluorescence tile at a grid position of a level. */
	static int channelValue(int channel, int level, int gx, int gy) {
		return channel == 0 ? (40 + 40 * gx + 10 * level) & 0xFF : (220 - 40 * gy - 10 * level) & 0xFF;
	}

	static Path tempFile(String suffix) throws IOException {
		Path path = Files.createTempFile("kfbio-test-", suffix);
		path.toFile().deleteOnExit();
		return path;
	}

	static byte[] jpegRgb(int w, int h, int rgb) throws IOException {
		var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(rgb));
		g.fillRect(0, 0, w, h);
		g.dispose();
		return encode(img);
	}

	static byte[] jpegGray(int w, int h, int value) throws IOException {
		var img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
		img.getRaster().setSamples(0, 0, w, h, 0, filled(w * h, value));
		return encode(img);
	}

	private static int[] filled(int n, int value) {
		var a = new int[n];
		java.util.Arrays.fill(a, value);
		return a;
	}

	private static byte[] encode(BufferedImage img) throws IOException {
		var out = new ByteArrayOutputStream();
		if (!ImageIO.write(img, "jpeg", out))
			throw new IOException("No JPEG writer available");
		return out.toByteArray();
	}

	private record TilePos(int level, int gx, int gy, int x, int y, int w, int h) {}

	/** Write a file and return the number of tile positions in it. */
	static int write(Path path, Spec s) throws IOException {
		// Enumerate tile positions the same way a scanner would: a grid per level
		int levelCount = (int) Math.max(0, Math.ceil(Math.log((double) Math.max(s.width, s.height) / s.tileSize) / Math.log(2))) + 1;
		var tiles = new ArrayList<TilePos>();
		for (int level = 0; level < levelCount; level++) {
			if (s.omitLevels.contains(level))
				continue;
			int div = 1 << level;
			int lw = (s.width + div - 1) / div;
			int lh = (s.height + div - 1) / div;
			for (int gy = 0; gy * s.tileSize < lh; gy++) {
				for (int gx = 0; gx * s.tileSize < lw; gx++) {
					if (s.omitTiles.contains(level + ":" + gx + ":" + gy))
						continue;
					int x = gx * s.tileSize;
					int y = gy * s.tileSize;
					tiles.add(new TilePos(level, gx, gy, x, y, Math.min(s.tileSize, lw - x), Math.min(s.tileSize, lh - y)));
				}
			}
		}

		int channels = s.fluorescence ? s.channelNames.size() : 1;
		var payloads = new ArrayList<byte[][]>();
		for (var t : tiles) {
			var perChannel = new byte[channels][];
			for (int c = 0; c < channels; c++) {
				perChannel[c] = s.fluorescence
						? jpegGray(t.w, t.h, channelValue(c, t.level, t.gx, t.gy))
						: jpegRgb(t.w, t.h, brightfieldColor(t.level, t.gx, t.gy));
			}
			payloads.add(perChannel);
		}

		var file = new ByteArrayOutputStream();
		file.write(new byte[HEADER_SIZE]);
		long thumbnailOffset = 0;

		if (s.associated) {
			thumbnailOffset = file.size();
			file.write(associatedSection(0x02, 64, 64, jpegRgb(64, 64, THUMBNAIL_RGB), -1));
		}
		long indexOffset;
		byte[] index;
		if (!s.fluorescence) {
			int n = tiles.size();
			long payloadTotal = 0;
			for (var p : payloads)
				payloadTotal += p[0].length;
			if (!s.payloadBeforeIndex) {
				if (s.associated)
					file.write(associatedSection(0x03, 50, 30, jpegRgb(50, 30, LABEL_RGB), -1));
				indexOffset = file.size();
				long cursor = indexOffset + (long) n * 64;
				var records = new ByteArrayOutputStream();
				for (int i = 0; i < n; i++) {
					records.write(tileRecord(s, tiles.get(i), payloads.get(i)[0].length, cursor - indexOffset, 0));
					cursor += payloads.get(i)[0].length;
				}
				index = records.toByteArray();
				file.write(index);
				for (var p : payloads)
					file.write(p[0]);
			} else {
				long payloadStart = file.size();
				indexOffset = payloadStart + payloadTotal;
				var records = new ByteArrayOutputStream();
				long cursor = payloadStart;
				for (int i = 0; i < n; i++) {
					records.write(tileRecord(s, tiles.get(i), payloads.get(i)[0].length, cursor - indexOffset, 0));
					cursor += payloads.get(i)[0].length;
				}
				for (var p : payloads)
					file.write(p[0]);
				index = records.toByteArray();
				file.write(index);
				if (s.associated)
					file.write(associatedSection(0x03, 50, 30, jpegRgb(50, 30, LABEL_RGB), -1));
			}
		} else {
			if (s.associated)
				file.write(associatedSection(0x03, 50, 30, jpegRgb(50, 30, LABEL_RGB), -1));
			// For each tile: offset table, length table, then the per-channel JPEGs
			var tableOffsets = new long[tiles.size()];
			var blocks = new ArrayList<byte[]>();
			long cursor = file.size();
			for (int i = 0; i < tiles.size(); i++) {
				var p = payloads.get(i);
				long offsetTable = cursor;
				long lengthTable = cursor + 8L * channels;
				long dataStart = cursor + 16L * channels;
				var block = ByteBuffer.allocate(16 * channels + sum(p)).order(ByteOrder.LITTLE_ENDIAN);
				long dataCursor = dataStart;
				for (int c = 0; c < channels; c++) {
					block.putLong(dataCursor);
					dataCursor += p[c].length;
				}
				for (int c = 0; c < channels; c++)
					block.putLong(p[c].length);
				for (int c = 0; c < channels; c++)
					block.put(p[c]);
				blocks.add(block.array());
				tableOffsets[i] = offsetTable;
				cursor += block.capacity();
			}
			for (var b : blocks)
				file.write(b);
			indexOffset = file.size();
			var records = new ByteArrayOutputStream();
			for (int i = 0; i < tiles.size(); i++)
				records.write(tileRecord(s, tiles.get(i), 0, tableOffsets[i], tableOffsets[i] + 8L * channels));
			index = records.toByteArray();
			file.write(index);
		}
		if (s.associated) {
			int declared = s.corruptMacroLength ? 999_999 : -1;
			file.write(associatedSection(0x02, 80, 40, jpegRgb(80, 40, MACRO_RGB), declared));
		}

		byte[] bytes = file.toByteArray();
		writeHeader(bytes, s, tiles.size(), thumbnailOffset, indexOffset);
		Files.write(path, bytes);
		return tiles.size();
	}

	private static int sum(byte[][] parts) {
		int total = 0;
		for (var p : parts)
			total += p.length;
		return total;
	}

	private static byte[] associatedSection(int markerType, int w, int h, byte[] jpeg, int declaredLength) {
		var buf = ByteBuffer.allocate(52 + jpeg.length).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(new byte[]{(byte) 0xF1, (byte) markerType, (byte) 0xEE, (byte) 0xEE});
		buf.position(8);
		buf.putInt(h);
		buf.putInt(w);
		buf.putInt(3);
		buf.putInt(declaredLength >= 0 ? declaredLength : jpeg.length);
		buf.putInt(52);
		buf.position(52);
		buf.put(jpeg);
		return buf.array();
	}

	private static byte[] tileRecord(Spec s, TilePos t, int dataLength, long dataOffset, long lengthTable) {
		var buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(new byte[]{(byte) 0xF1, 0x04, (byte) 0xEE, (byte) 0xEE});
		buf.putInt(t.x);
		buf.putInt(t.y);
		buf.putInt(t.w);
		buf.putInt(t.h);
		buf.putFloat(s.scanScale / (float) (1 << t.level));
		buf.position(32);
		buf.putInt(dataLength);
		buf.putLong(dataOffset);
		buf.putLong(lengthTable);
		buf.position(60);
		buf.put(new byte[]{(byte) 0xFF, 0x04, (byte) 0xEE, (byte) 0xEE});
		return buf.array();
	}

	private static void writeHeader(byte[] bytes, Spec s, int tileCount, long thumbnailOffset, long indexOffset) {
		var buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(0, (byte) 0xF1).put(1, (byte) 0x01).put(2, (byte) 0xEE).put(3, (byte) 0xEE);
		if (s.fluorescence) {
			for (int i = 0; i < 4; i++)
				buf.put(4 + i, (byte) "KFBF".charAt(i));
		}
		buf.putInt(0x10, tileCount);
		buf.putInt(0x14, s.height);
		buf.putInt(0x18, s.width);
		buf.putInt(0x1C, s.scanScale);
		for (int i = 0; i < 4; i++)
			buf.put(0x20 + i, (byte) "JPEG".charAt(i));
		buf.putInt(0x28, 123);
		buf.putInt(0x2C, 1_700_000_000);
		buf.putLong(0x3C, thumbnailOffset);
		buf.putLong(0x44, indexOffset);
		buf.putFloat(0x4C, s.mpp);
		buf.putInt(0x58, s.tileSize);
		if (s.fluorescence && s.channelMetadata)
			writeChannelMetadata(bytes, s);
	}

	/** Same layout as kfb2zarr's test helper: three TLV entries at 0xA8, followed by names, colours and exposures. */
	private static void writeChannelMetadata(byte[] bytes, Spec s) {
		int n = s.channelNames.size();
		int tlvBase = 0xA8;
		int namesOffset = tlvBase + 48;
		int colorsOffset = namesOffset + n * 40;
		int exposuresOffset = colorsOffset + n * 12;
		if (exposuresOffset + n * 8 > HEADER_SIZE)
			throw new IllegalArgumentException("Too many channels for the synthetic header");

		var be = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
		var le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		int[] tags = {0x4D, 0x4F, 0x54};
		int[] offsets = {namesOffset, colorsOffset, exposuresOffset};
		for (int i = 0; i < 3; i++) {
			int pos = tlvBase + i * 16;
			be.putInt(pos, tags[i]);
			be.putInt(pos + 4, 8);
			le.putInt(pos + 8 + 3, offsets[i]);
		}
		for (int i = 0; i < n; i++) {
			byte[] name = s.channelNames.get(i).getBytes(StandardCharsets.UTF_8);
			System.arraycopy(name, 0, bytes, namesOffset + i * 40, name.length);
			for (int k = 0; k < 3; k++)
				le.putInt(colorsOffset + i * 12 + k * 4, s.channelColors[i][k]);
			le.putDouble(exposuresOffset + i * 8, s.exposures[i]);
		}
	}
}
