package qupath.ext.kfbio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Low-level reader for KFBio whole slide image containers ({@code .kfb} brightfield, {@code .kfbf} fluorescence).
 * <p>
 * This is a Java port of the container parsing in <a href="https://github.com/camlloyd/kfb2zarr">kfb2zarr</a>
 * by Cameron Lloyd (MIT OR Apache-2.0), which in turn is based on reverse engineering of the format.
 * Nothing here is based on a vendor specification, so every field below should be considered
 * "as understood by kfb2zarr", not "as documented by KFBio".
 * <p>
 * Layout, as far as it is understood (all offsets are absolute file offsets, integers little-endian unless noted):
 * <pre>
 *   0x00  F1 01 EE EE            magic ("KFBF" at 0x04 for fluorescence)
 *   0x10  i32 tile count
 *   0x14  i32 image height       (both formats)
 *   0x18  i32 image width        (both formats)
 *   0x1C  i32 scan scale         (objective magnification, e.g. 20 or 40)
 *   0x20  8 bytes codec label
 *   0x28  i32 scan duration (s)
 *   0x2C  i32 scan time (unix seconds)
 *   0x3C  u64 offset of the thumbnail section
 *   0x44  u64 offset of the tile index
 *   0x4C  f32 microns per pixel
 *   0x58  i32 nominal tile size
 *   0xA8  KFBF only: TLV block locating channel names / colours / exposures
 *   0xB0  KFBF only: u32 (big-endian) channel count
 * </pre>
 * The tile index is {@code tileCount} records of 64 bytes ({@code F1 04 EE EE} magic). Each record holds the tile
 * position in the pixel space of its own resolution level, its size, a magnification (from which the pyramid
 * level is derived) and the location of the JPEG data. In brightfield files this is an offset relative to the
 * start of the tile index plus a length; in fluorescence files it points to per-channel offset and length tables.
 */
public final class KfbFile implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(KfbFile.class);

	private static final int HEADER_READ_SIZE = 0x400;
	private static final int TILE_RECORD_SIZE = 64;
	private static final int ASSOCIATED_HEADER_SIZE = 52;
	private static final int RECORDS_PER_READ = 16 * 1024;
	private static final int SCAN_CHUNK = 1 << 20;
	/** Upper bound on how far to search for the end of an associated image JPEG whose declared length looks wrong. */
	private static final long MAX_ASSOCIATED_SEARCH = 64L << 20;
	/** Upper bound on the number of pyramid levels we are prepared to believe. */
	private static final int MAX_LEVELS = 40;

	/** The kind of slide this file contains. */
	public enum Format {
		/** RGB brightfield slide ({@code .kfb}). */
		BRIGHTFIELD,
		/** Multichannel fluorescence slide ({@code .kfbf}). */
		FLUORESCENCE
	}

	/** The role of an image embedded in the file next to the main pyramid. */
	public enum AssociatedKind {
		/** Slide label. */
		LABEL("label"),
		/** Low-resolution overview of the whole glass slide. */
		MACRO("macro"),
		/** Thumbnail of the scanned area. */
		THUMBNAIL("thumbnail");

		private final String imageName;

		AssociatedKind(String imageName) {
			this.imageName = imageName;
		}

		/** Name under which this image is exposed as an associated image in QuPath. */
		public String imageName() {
			return imageName;
		}
	}

	/**
	 * Per-channel metadata of a fluorescence file.
	 * @param name channel name as recorded by the scanner
	 * @param rgb display colour packed as 0xRRGGBB
	 * @param exposureMs exposure time in milliseconds
	 */
	public record ChannelInfo(String name, int rgb, double exposureMs) {}

	/**
	 * Parsed file header.
	 * @param format brightfield or fluorescence
	 * @param tileCount number of tile records in the tile index
	 * @param baseWidth full-resolution width according to the header
	 * @param baseHeight full-resolution height according to the header
	 * @param scanScale scan magnification
	 * @param spendTimeSeconds scan duration
	 * @param scanTime scan time (unix seconds)
	 * @param mpp microns per pixel at full resolution
	 * @param tileSize nominal tile size
	 * @param channelCount number of channels (3 for brightfield)
	 * @param channels channel metadata (empty for brightfield, or when the KFBF header lacks it)
	 * @param thumbnailOffset file offset of the thumbnail section
	 * @param tileIndexOffset file offset of the tile index
	 */
	public record Header(Format format, int tileCount, int baseWidth, int baseHeight, int scanScale,
						 int spendTimeSeconds, long scanTime, double mpp, int tileSize, int channelCount,
						 List<ChannelInfo> channels, long thumbnailOffset, long tileIndexOffset) {}

	/**
	 * One JPEG-compressed tile (for fluorescence files: one channel of one tile position).
	 * @param ordinal order of discovery in the file; used to resolve overlaps the same way kfb2zarr does
	 * @param posX x of the top-left corner, in pixels of this tile's own resolution level
	 * @param posY y of the top-left corner, in pixels of this tile's own resolution level
	 * @param width tile width according to the tile record
	 * @param height tile height according to the tile record
	 * @param channel channel index (always 0 for brightfield)
	 * @param zoomLevel pyramid level; 0 is full resolution
	 * @param dataOffset absolute file offset of the JPEG data
	 * @param dataLength length of the JPEG data in bytes
	 */
	public record Tile(int ordinal, int posX, int posY, int width, int height, int channel, int zoomLevel,
					   long dataOffset, int dataLength) {}

	/**
	 * An image embedded in the file outside the pyramid.
	 * @param kind label, macro or thumbnail
	 * @param width declared width
	 * @param height declared height
	 * @param dataOffset absolute file offset of the JPEG data
	 * @param dataLength length of the JPEG data in bytes
	 */
	public record AssociatedImage(AssociatedKind kind, int width, int height, long dataOffset, int dataLength) {}

	/** One resolution level of the pyramid. */
	public static final class Level {

		private final int zoomLevel;
		private final int width;
		private final int height;
		private final List<Tile> tiles;
		private final int bucketSize;
		private volatile Map<Long, List<Tile>> buckets;

		private Level(int zoomLevel, int width, int height, List<Tile> tiles, int bucketSize) {
			this.zoomLevel = zoomLevel;
			this.width = width;
			this.height = height;
			this.tiles = List.copyOf(tiles);
			this.bucketSize = Math.max(1, bucketSize);
		}

		/** Zoom level index; 0 is full resolution, each further level halves the size. */
		public int zoomLevel() {
			return zoomLevel;
		}

		/** Width of this level in pixels. */
		public int width() {
			return width;
		}

		/** Height of this level in pixels. */
		public int height() {
			return height;
		}

		/** All tiles (with data) belonging to this level, in file order. */
		public List<Tile> tiles() {
			return tiles;
		}

		/**
		 * Find all tiles that overlap a rectangle, in file order.
		 * Tiles are not assumed to be aligned to any grid.
		 * @param x left edge in pixels of this level
		 * @param y top edge in pixels of this level
		 * @param w width of the rectangle
		 * @param h height of the rectangle
		 * @return the overlapping tiles (all channels), sorted by {@link Tile#ordinal()}
		 */
		public List<Tile> tilesIntersecting(int x, int y, int w, int h) {
			if (w <= 0 || h <= 0)
				return List.of();
			var map = getBuckets();
			int gx0 = Math.floorDiv(Math.max(x, 0), bucketSize);
			int gy0 = Math.floorDiv(Math.max(y, 0), bucketSize);
			int gx1 = Math.floorDiv(Math.max(x + w - 1, 0), bucketSize);
			int gy1 = Math.floorDiv(Math.max(y + h - 1, 0), bucketSize);
			var found = new LinkedHashSet<Tile>();
			for (int gy = gy0; gy <= gy1; gy++) {
				for (int gx = gx0; gx <= gx1; gx++) {
					var list = map.get(key(gx, gy));
					if (list == null)
						continue;
					for (var tile : list) {
						if (tile.posX() < x + w && tile.posX() + tile.width() > x &&
								tile.posY() < y + h && tile.posY() + tile.height() > y)
							found.add(tile);
					}
				}
			}
			var result = new ArrayList<>(found);
			result.sort(Comparator.comparingInt(Tile::ordinal));
			return result;
		}

		private Map<Long, List<Tile>> getBuckets() {
			var map = buckets;
			if (map == null) {
				synchronized (this) {
					map = buckets;
					if (map == null) {
						map = new HashMap<>();
						for (var tile : tiles) {
							if (tile.posX() < 0 || tile.posY() < 0 || tile.width() <= 0 || tile.height() <= 0)
								continue;
							int gx1 = (int) (((long) tile.posX() + tile.width() - 1) / bucketSize);
							int gy1 = (int) (((long) tile.posY() + tile.height() - 1) / bucketSize);
							for (int gy = tile.posY() / bucketSize; gy <= gy1; gy++) {
								for (int gx = tile.posX() / bucketSize; gx <= gx1; gx++)
									map.computeIfAbsent(key(gx, gy), k -> new ArrayList<>()).add(tile);
							}
						}
						buckets = map;
					}
				}
			}
			return map;
		}

		private static long key(int gx, int gy) {
			return ((long) gx << 32) | (gy & 0xFFFFFFFFL);
		}
	}

	/**
	 * The pyramid derived from the tile records.
	 * @param tileSize width and height of a full tile
	 * @param levels levels indexed by zoom level; some may contain no tiles
	 */
	public record Pyramid(int tileSize, List<Level> levels) {}

	private final Path path;
	private final FileChannel channel;
	private final long fileSize;
	private final Header header;
	private final List<Tile> tiles;
	private final Pyramid pyramid;
	private List<AssociatedImage> associated;

	/**
	 * Open a KFB or KFBF file and parse its header and tile index.
	 * @param path the file
	 * @return an open file; the caller must close it
	 * @throws IOException if the file cannot be read or does not look like a KFB file
	 */
	public static KfbFile open(Path path) throws IOException {
		var channel = FileChannel.open(path, StandardOpenOption.READ);
		try {
			return new KfbFile(path, channel);
		} catch (IOException | RuntimeException e) {
			try {
				channel.close();
			} catch (IOException suppressed) {
				e.addSuppressed(suppressed);
			}
			throw e;
		}
	}

	/**
	 * Check whether a file starts with the KFB magic bytes, without parsing anything else.
	 * @param path the file
	 * @return true if the magic bytes are present
	 */
	public static boolean hasKfbMagic(Path path) {
		try (var ch = FileChannel.open(path, StandardOpenOption.READ)) {
			var buf = ByteBuffer.allocate(4);
			while (buf.hasRemaining()) {
				if (ch.read(buf, buf.position()) < 0)
					return false;
			}
			return buf.get(0) == (byte) 0xF1 && buf.get(1) == 0x01 && buf.get(2) == (byte) 0xEE && buf.get(3) == (byte) 0xEE;
		} catch (IOException | RuntimeException e) {
			logger.debug("Unable to check magic bytes of {}: {}", path, e.getMessage());
			return false;
		}
	}

	private KfbFile(Path path, FileChannel channel) throws IOException {
		this.path = path;
		this.channel = channel;
		this.fileSize = channel.size();
		this.header = parseHeader();
		this.tiles = scanTiles();
		this.pyramid = buildPyramid();
	}

	/** The file this was opened from. */
	public Path path() {
		return path;
	}

	/** The parsed header. */
	public Header header() {
		return header;
	}

	/** Every tile found in the tile index (all channels, all levels), in file order. */
	public List<Tile> tiles() {
		return tiles;
	}

	/** The pyramid derived from the tile records. */
	public Pyramid pyramid() {
		return pyramid;
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}

	// ------------------------------------------------------------------------------------------------------------
	// Header
	// ------------------------------------------------------------------------------------------------------------

	private Header parseHeader() throws IOException {
		byte[] head = readUpTo(0, (int) Math.min(fileSize, HEADER_READ_SIZE));
		if (head.length < 4 || head[0] != (byte) 0xF1 || head[1] != 0x01 || head[2] != (byte) 0xEE || head[3] != (byte) 0xEE)
			throw new IOException("Not a KFB file (bad magic bytes): " + path);
		if (head.length < 0x5C)
			throw new IOException("File too short to hold a KFB header: " + path);

		boolean fluorescence = head[4] == 'K' && head[5] == 'F' && head[6] == 'B' && head[7] == 'F';
		var le = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
		int tileCount = le.getInt(0x10);
		int baseHeight = le.getInt(0x14);
		int baseWidth = le.getInt(0x18);
		int scanScale = le.getInt(0x1C);
		int spendTime = le.getInt(0x28);
		long scanTime = le.getInt(0x2C);
		long thumbnailOffset = le.getLong(0x3C);
		long tileIndexOffset = le.getLong(0x44);
		double mpp = le.getFloat(0x4C);
		int tileSize = le.getInt(0x58);

		if (baseWidth <= 0 || baseHeight <= 0)
			throw new IOException("Invalid KFB image size " + baseWidth + " x " + baseHeight + ": " + path);

		int channelCount = 3;
		List<ChannelInfo> channels = List.of();
		if (fluorescence) {
			channelCount = 1;
			if (head.length >= 0xB4) {
				long count = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN).getInt(0xB0) & 0xFFFFFFFFL;
				if (count > 0 && count < 32)
					channelCount = (int) count;
			}
			var parsed = parseFluorescenceChannels(head, channelCount);
			channelCount = parsed.count;
			channels = parsed.channels;
		}

		return new Header(fluorescence ? Format.FLUORESCENCE : Format.BRIGHTFIELD, tileCount, baseWidth, baseHeight,
				scanScale, spendTime, scanTime, mpp, tileSize, channelCount, channels, thumbnailOffset, tileIndexOffset);
	}

	private record ParsedChannels(int count, List<ChannelInfo> channels) {}

	/**
	 * Parse the KFBF TLV block at 0xA8. Mirrors kfb2zarr: three tags (0x4D names, 0x4F colours, 0x54 exposures),
	 * each 16 bytes: u32 BE tag, u32 BE length (8), then 8 bytes of which bytes 3..7 hold a little-endian u32 file
	 * offset. The channel count is inferred from the distance between the names and colours blocks (40 bytes per name).
	 */
	private ParsedChannels parseFluorescenceChannels(byte[] head, int fallbackCount) throws IOException {
		final int tagNames = 0x4D, tagColors = 0x4F, tagExposures = 0x54, scanLimit = 0x200;
		long namesOffset = -1, colorsOffset = -1, exposuresOffset = -1;

		var be = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN);
		var le = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
		long pos = 0xA8;
		while (pos + 8 <= head.length) {
			long tag = be.getInt((int) pos) & 0xFFFFFFFFL;
			long len = be.getInt((int) pos + 4) & 0xFFFFFFFFL;
			if (tag == 0 || pos > scanLimit)
				break;
			if (len == 8) {
				if (pos + 16 > head.length)
					break;
				long offset = le.getInt((int) pos + 8 + 3) & 0xFFFFFFFFL;
				if (tag == tagNames)
					namesOffset = offset;
				else if (tag == tagColors)
					colorsOffset = offset;
				else if (tag == tagExposures)
					exposuresOffset = offset;
			}
			pos += 8 + len;
			if (namesOffset >= 0 && colorsOffset >= 0 && exposuresOffset >= 0)
				break;
		}
		if (namesOffset < 0 || colorsOffset < 0 || exposuresOffset < 0)
			return new ParsedChannels(fallbackCount, List.of());

		int count = colorsOffset > namesOffset ? (int) ((colorsOffset - namesOffset) / 40) : fallbackCount;
		if (count <= 0 || count >= 256)
			return new ParsedChannels(fallbackCount, List.of());

		byte[] names = readOrNull(namesOffset, count * 40);
		byte[] colors = readOrNull(colorsOffset, count * 12);
		byte[] exposures = readOrNull(exposuresOffset, count * 8);
		if (names == null || colors == null || exposures == null)
			return new ParsedChannels(fallbackCount, List.of());

		var colorBuf = ByteBuffer.wrap(colors).order(ByteOrder.LITTLE_ENDIAN);
		var exposureBuf = ByteBuffer.wrap(exposures).order(ByteOrder.LITTLE_ENDIAN);
		var list = new ArrayList<ChannelInfo>(count);
		for (int i = 0; i < count; i++) {
			int end = 0;
			while (end < 40 && names[i * 40 + end] != 0)
				end++;
			String name = new String(names, i * 40, end, StandardCharsets.UTF_8);
			// Each colour component is stored as a u32; kfb2zarr keeps the low byte of each
			int r = colorBuf.getInt(i * 12) & 0xFF;
			int g = colorBuf.getInt(i * 12 + 4) & 0xFF;
			int b = colorBuf.getInt(i * 12 + 8) & 0xFF;
			list.add(new ChannelInfo(name, (r << 16) | (g << 8) | b, exposureBuf.getDouble(i * 8)));
		}
		return new ParsedChannels(count, List.copyOf(list));
	}

	// ------------------------------------------------------------------------------------------------------------
	// Tile index
	// ------------------------------------------------------------------------------------------------------------

	private List<Tile> scanTiles() throws IOException {
		final long indexOffset = header.tileIndexOffset();
		final int count = Math.max(0, header.tileCount());
		final boolean fluorescence = header.format() == Format.FLUORESCENCE;
		final int channelCount = header.channelCount();
		final float maxMag = header.scanScale();

		if (indexOffset < 0 || indexOffset + (long) count * TILE_RECORD_SIZE > fileSize)
			throw new IOException(String.format("Tile index (offset %d, %d records) lies outside the file (%d bytes): %s",
					indexOffset, count, fileSize, path));

		var result = new ArrayList<Tile>(fluorescence ? count * channelCount : count);
		int ordinal = 0;
		for (int first = 0; first < count; first += RECORDS_PER_READ) {
			int n = Math.min(RECORDS_PER_READ, count - first);
			long chunkOffset = indexOffset + (long) first * TILE_RECORD_SIZE;
			var buf = ByteBuffer.wrap(readExactly(chunkOffset, n * TILE_RECORD_SIZE)).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < n; i++) {
				int r = i * TILE_RECORD_SIZE;
				long recordOffset = chunkOffset + r;
				if (buf.get(r) != (byte) 0xF1 || buf.get(r + 1) != 0x04 || buf.get(r + 2) != (byte) 0xEE || buf.get(r + 3) != (byte) 0xEE)
					throw new IOException("Invalid tile record magic at offset " + recordOffset + " in " + path);

				int x = buf.getInt(r + 4);
				int y = buf.getInt(r + 8);
				int w = buf.getInt(r + 12);
				int h = buf.getInt(r + 16);
				float mag = buf.getFloat(r + 20);
				int dataLength = buf.getInt(r + 32);
				long dataOffset = buf.getLong(r + 36);
				long lengthTable = buf.getLong(r + 44);

				// Level 0 is full resolution; each level halves the magnification
				int zoom = 0;
				if (mag > 0 && maxMag > 0)
					zoom = (int) Math.round(Math.log(maxMag / (double) mag) / Math.log(2));

				if (!fluorescence) {
					result.add(new Tile(ordinal++, x, y, w, h, 0, zoom, indexOffset + dataOffset, dataLength));
					continue;
				}

				// Fluorescence: dataOffset points to a table of u64 JPEG offsets, lengthTable to a table of u64 lengths
				var offsets = ByteBuffer.wrap(readExactly(dataOffset, channelCount * 8)).order(ByteOrder.LITTLE_ENDIAN);
				var lengths = ByteBuffer.wrap(readExactly(lengthTable, channelCount * 8)).order(ByteOrder.LITTLE_ENDIAN);
				for (int c = 0; c < channelCount; c++) {
					long length = lengths.getLong(c * 8);
					if (length < 0 || length > Integer.MAX_VALUE)
						throw new IOException("Implausible tile data length " + length + " in " + path);
					result.add(new Tile(ordinal++, x, y, w, h, c, zoom, offsets.getLong(c * 8), (int) length));
				}
			}
		}
		return List.copyOf(result);
	}

	// ------------------------------------------------------------------------------------------------------------
	// Pyramid
	// ------------------------------------------------------------------------------------------------------------

	/**
	 * Work out the levels from the tile records, following kfb2zarr's {@code Pyramid::new}:
	 * the tile size is the largest level-0 tile record (or the header's nominal size), the number of levels is
	 * enough to reduce the largest dimension to a single tile, and each level halves the one before, rounding up.
	 * Level 0 is extended to cover every level-0 tile.
	 */
	private Pyramid buildPyramid() {
		var withData = tiles.stream().filter(t -> t.dataLength() > 0).toList();

		int tileSize = withData.stream()
				.filter(t -> t.zoomLevel() == 0)
				.mapToInt(t -> Math.max(t.width(), t.height()))
				.max()
				.orElse(header.tileSize());
		if (tileSize <= 0)
			tileSize = Math.max(1, header.tileSize());
		if (tileSize <= 0)
			tileSize = 256;

		double maxDim = Math.max(header.baseWidth(), header.baseHeight());
		int tileLevels = (int) Math.max(0, Math.ceil(Math.log(maxDim / tileSize) / Math.log(2)));
		int levelCount = Math.min(tileLevels + 1, MAX_LEVELS);

		List<List<Tile>> byLevel = new ArrayList<>();
		for (int i = 0; i < levelCount; i++)
			byLevel.add(new ArrayList<>());
		for (var tile : withData) {
			if (tile.zoomLevel() >= 0 && tile.zoomLevel() < levelCount)
				byLevel.get(tile.zoomLevel()).add(tile);
		}

		long width = header.baseWidth();
		long height = header.baseHeight();
		for (var tile : byLevel.get(0)) {
			width = Math.max(width, Math.max(0L, (long) tile.posX() + tile.width()));
			height = Math.max(height, Math.max(0L, (long) tile.posY() + tile.height()));
		}
		if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE)
			throw new IllegalStateException("Image is too large for QuPath: " + width + " x " + height);

		var levels = new ArrayList<Level>(levelCount);
		for (int i = 0; i < levelCount; i++) {
			long div = 1L << i;
			levels.add(new Level(i, (int) ((width + div - 1) / div), (int) ((height + div - 1) / div), byLevel.get(i), tileSize));
		}
		return new Pyramid(tileSize, List.copyOf(levels));
	}

	// ------------------------------------------------------------------------------------------------------------
	// Tile and associated image data
	// ------------------------------------------------------------------------------------------------------------

	/**
	 * Read the raw JPEG bytes of a tile.
	 * @param tile the tile
	 * @return JPEG data
	 * @throws IOException if the recorded range lies outside the file
	 */
	public byte[] readTileBytes(Tile tile) throws IOException {
		return readRange(tile.dataOffset(), tile.dataLength());
	}

	/**
	 * Read the raw JPEG bytes of an associated image.
	 * @param image the associated image
	 * @return JPEG data
	 * @throws IOException if the recorded range lies outside the file
	 */
	public byte[] readAssociatedBytes(AssociatedImage image) throws IOException {
		return readRange(image.dataOffset(), image.dataLength());
	}

	private byte[] readRange(long offset, int length) throws IOException {
		if (offset < 0 || length < 0 || offset + length > fileSize)
			throw new IOException(String.format("Data range %d + %d lies outside the file (%d bytes): %s",
					offset, length, fileSize, path));
		return readExactly(offset, length);
	}

	/**
	 * Get the label, macro and thumbnail images, if any.
	 * <p>
	 * These are not listed in any index that we know of. kfb2zarr finds them by scanning the whole file for section
	 * markers, skipping tile data. We do the same, but only on first request (it can mean reading every gap between
	 * tiles) and never fail: problems are logged and result in fewer images.
	 * @return the associated images found
	 */
	public synchronized List<AssociatedImage> associatedImages() {
		if (associated == null) {
			try {
				associated = List.copyOf(scanAssociatedImages());
			} catch (IOException | RuntimeException e) {
				logger.warn("Unable to scan {} for associated images: {}", path, e.getMessage());
				logger.debug(e.getMessage(), e);
				associated = List.of();
			}
		}
		return associated;
	}

	private List<AssociatedImage> scanAssociatedImages() throws IOException {
		// Everything that is known not to hold section markers: tile payloads and the tile index itself
		var skip = new ArrayList<long[]>();
		for (var tile : tiles) {
			if (tile.dataLength() >= 0)
				skip.add(new long[]{tile.dataOffset(), tile.dataOffset() + tile.dataLength()});
		}
		skip.add(new long[]{header.tileIndexOffset(), header.tileIndexOffset() + (long) Math.max(0, header.tileCount()) * TILE_RECORD_SIZE});
		skip.sort(Comparator.comparingLong(a -> a[0]));

		var found = new ArrayList<AssociatedImage>();
		long cursor = 0;
		for (var range : skip) {
			if (range[0] > cursor)
				cursor = scanGap(cursor, Math.min(range[0], fileSize), found);
			cursor = Math.max(cursor, range[1]);
		}
		if (cursor < fileSize)
			scanGap(cursor, fileSize, found);
		return found;
	}

	private long scanGap(long from, long to, List<AssociatedImage> found) throws IOException {
		long pos = from;
		while (pos + 4 <= to) {
			long marker = findMarker(pos, to, false);
			if (marker < 0 || marker + 56 > fileSize)
				break;

			byte[] id = readExactly(marker, 4);
			AssociatedKind kind;
			if (id[1] == 0x03) {
				kind = AssociatedKind.LABEL;
			} else {
				// The macro and the thumbnail share a marker; the header's pointer identifies the thumbnail
				kind = marker == header.thumbnailOffset() ? AssociatedKind.THUMBNAIL : AssociatedKind.MACRO;
			}
			final var currentKind = kind;
			if (found.stream().anyMatch(a -> a.kind() == currentKind)) {
				pos = marker + 1;
				continue;
			}

			var section = ByteBuffer.wrap(readExactly(marker + 8, 16)).order(ByteOrder.LITTLE_ENDIAN);
			int height = section.getInt(0);
			int width = section.getInt(4);
			int dataLength = section.getInt(12);
			var candidate = new AssociatedImage(kind, width, height, marker + ASSOCIATED_HEADER_SIZE, dataLength);
			var normalized = normalizeJpegLength(candidate);
			if (normalized == null) {
				pos = marker + 1;
				continue;
			}
			found.add(normalized);
			pos = normalized.dataOffset() + normalized.dataLength();
		}
		return Math.max(pos, from);
	}

	/**
	 * Make sure an associated image really is a JPEG with a usable length: it must start with an SOI marker, and
	 * either end with an EOI marker at its declared length, or else end at the first EOI before the next section.
	 * @return the image (possibly with a corrected length) or null if it does not look like a JPEG
	 */
	private AssociatedImage normalizeJpegLength(AssociatedImage image) throws IOException {
		long start = image.dataOffset();
		byte[] soi = readOrNull(start, 2);
		if (soi == null || soi[0] != (byte) 0xFF || soi[1] != (byte) 0xD8)
			return null;

		if (image.dataLength() >= 4 && start + image.dataLength() <= fileSize) {
			byte[] tail = readOrNull(start + image.dataLength() - 2, 2);
			if (tail != null && tail[0] == (byte) 0xFF && tail[1] == (byte) 0xD9)
				return image;
		}

		long searchEnd = findMarker(start, Math.min(fileSize, start + MAX_ASSOCIATED_SEARCH), true);
		if (searchEnd < 0)
			searchEnd = Math.min(fileSize, start + MAX_ASSOCIATED_SEARCH);
		long eoi = findEoi(start, searchEnd);
		if (eoi < 0 || eoi - start < 4)
			return null;
		return new AssociatedImage(image.kind(), image.width(), image.height(), start, (int) (eoi - start));
	}

	/**
	 * Find the next section marker.
	 * @param anyMarker if true, any known marker (header, thumbnail/macro, label, tile, and FF 01 EE EE) matches;
	 *                  otherwise only the thumbnail/macro and label markers
	 * @return absolute position, or -1 if none was found before {@code to}
	 */
	private long findMarker(long from, long to, boolean anyMarker) throws IOException {
		var buf = new byte[SCAN_CHUNK + 3];
		long pos = from;
		while (pos + 4 <= to) {
			int want = (int) Math.min(buf.length, to - pos);
			int got = readInto(buf, pos, want);
			if (got < 4)
				break;
			int limit = got - 3;
			for (int i = 0; i < limit; i++) {
				if (buf[i + 2] != (byte) 0xEE || buf[i + 3] != (byte) 0xEE)
					continue;
				int b0 = buf[i] & 0xFF;
				int b1 = buf[i + 1] & 0xFF;
				boolean hit;
				if (anyMarker)
					hit = (b0 == 0xF1 && b1 >= 0x01 && b1 <= 0x04) || (b0 == 0xFF && b1 == 0x01);
				else
					hit = b0 == 0xF1 && (b1 == 0x02 || b1 == 0x03);
				if (hit)
					return pos + i;
			}
			pos += limit;
		}
		return -1;
	}

	/** @return the position just after the first FF D9 in the range, or -1 */
	private long findEoi(long from, long to) throws IOException {
		var buf = new byte[SCAN_CHUNK + 1];
		long pos = from;
		while (pos + 2 <= to) {
			int want = (int) Math.min(buf.length, to - pos);
			int got = readInto(buf, pos, want);
			if (got < 2)
				break;
			int limit = got - 1;
			for (int i = 0; i < limit; i++) {
				if (buf[i] == (byte) 0xFF && buf[i + 1] == (byte) 0xD9)
					return pos + i + 2;
			}
			pos += limit;
		}
		return -1;
	}

	// ------------------------------------------------------------------------------------------------------------
	// Positional reads
	// ------------------------------------------------------------------------------------------------------------

	private int readInto(byte[] dst, long pos, int length) throws IOException {
		var buf = ByteBuffer.wrap(dst, 0, length);
		int total = 0;
		while (buf.hasRemaining()) {
			int n = channel.read(buf, pos + total);
			if (n < 0)
				break;
			total += n;
		}
		return total;
	}

	private byte[] readUpTo(long pos, int length) throws IOException {
		var dst = new byte[length];
		int got = readInto(dst, pos, length);
		return got == length ? dst : java.util.Arrays.copyOf(dst, got);
	}

	private byte[] readExactly(long pos, int length) throws IOException {
		if (pos < 0 || length < 0 || pos + length > fileSize)
			throw new EOFException(String.format("Read of %d bytes at offset %d lies outside the file (%d bytes): %s",
					length, pos, fileSize, path));
		var dst = new byte[length];
		int got = readInto(dst, pos, length);
		if (got != length)
			throw new EOFException("Unexpected end of file at offset " + (pos + got) + ": " + path);
		return dst;
	}

	private byte[] readOrNull(long pos, int length) throws IOException {
		if (pos < 0 || length < 0 || pos + length > fileSize)
			return null;
		return readExactly(pos, length);
	}
}
