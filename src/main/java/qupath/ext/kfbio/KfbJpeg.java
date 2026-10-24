package qupath.ext.kfbio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Decoding of the JPEG data embedded in KFB files.
 * <p>
 * kfb2zarr decodes with zune-jpeg in non-strict mode, which suggests that not every tile is a perfectly clean
 * JPEG. {@code ImageIO} is stricter, so if the normal route fails we retry by reading the raw raster
 * (which tolerates a few things the image-type detection does not, e.g. unusual colour spaces).
 * Whether this fallback is ever needed can only be known from real files.
 */
final class KfbJpeg {

	private static final Logger logger = LoggerFactory.getLogger(KfbJpeg.class);

	private KfbJpeg() {}

	/**
	 * A decoded RGB image.
	 * @param width width in pixels
	 * @param height height in pixels
	 * @param rgb pixels packed as 0xRRGGBB, row-major
	 */
	record Rgb(int width, int height, int[] rgb) {}

	/**
	 * A decoded single-channel image.
	 * @param width width in pixels
	 * @param height height in pixels
	 * @param pixels 8-bit samples, row-major
	 */
	record Luma(int width, int height, byte[] pixels) {}

	/**
	 * Decode a JPEG to RGB.
	 * @param jpeg compressed data
	 * @return the decoded image
	 * @throws IOException if the data cannot be decoded
	 */
	static Rgb decodeRgb(byte[] jpeg) throws IOException {
		try {
			var img = ImageIO.read(new ByteArrayInputStream(jpeg));
			if (img != null)
				return toRgb(img);
		} catch (IOException | RuntimeException e) {
			logger.debug("ImageIO could not read JPEG normally ({}), trying raster fallback", e.getMessage());
		}
		var raster = readRaster(jpeg);
		int w = raster.getWidth();
		int h = raster.getHeight();
		var out = new int[w * h];
		if (raster.getNumBands() == 1) {
			var gray = raster.getSamples(0, 0, w, h, 0, (int[]) null);
			for (int i = 0; i < out.length; i++)
				out[i] = (gray[i] << 16) | (gray[i] << 8) | gray[i];
		} else if (raster.getNumBands() == 3) {
			var px = new int[3];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					raster.getPixel(x, y, px);
					out[y * w + x] = ycbcrToRgb(px[0], px[1], px[2]);
				}
			}
		} else {
			throw new IOException("Unsupported JPEG with " + raster.getNumBands() + " components");
		}
		return new Rgb(w, h, out);
	}

	/**
	 * Decode a JPEG to a single 8-bit channel. Single-component JPEGs are returned as they are;
	 * for colour JPEGs the luma is returned.
	 * @param jpeg compressed data
	 * @return the decoded image
	 * @throws IOException if the data cannot be decoded
	 */
	static Luma decodeLuma(byte[] jpeg) throws IOException {
		try {
			var img = ImageIO.read(new ByteArrayInputStream(jpeg));
			if (img != null) {
				var raster = img.getRaster();
				int w = raster.getWidth();
				int h = raster.getHeight();
				if (raster.getNumBands() == 1 && raster.getSampleModel().getSampleSize(0) == 8)
					return new Luma(w, h, toBytes(raster.getSamples(0, 0, w, h, 0, (int[]) null)));
				var rgb = toRgb(img);
				var out = new byte[w * h];
				for (int i = 0; i < out.length; i++)
					out[i] = (byte) luma(rgb.rgb()[i]);
				return new Luma(w, h, out);
			}
		} catch (IOException | RuntimeException e) {
			logger.debug("ImageIO could not read JPEG normally ({}), trying raster fallback", e.getMessage());
		}
		var raster = readRaster(jpeg);
		int w = raster.getWidth();
		int h = raster.getHeight();
		// For a 3-component JPEG the first component is Y; for a 1-component JPEG it is the image itself
		return new Luma(w, h, toBytes(raster.getSamples(0, 0, w, h, 0, (int[]) null)));
	}

	private static Rgb toRgb(BufferedImage img) {
		int w = img.getWidth();
		int h = img.getHeight();
		var raster = img.getRaster();
		if (raster.getNumBands() == 1 && raster.getSampleModel().getSampleSize(0) == 8) {
			// BufferedImage.getRGB() would apply a gray colour space conversion to these values
			var gray = raster.getSamples(0, 0, w, h, 0, (int[]) null);
			var out = new int[w * h];
			for (int i = 0; i < out.length; i++)
				out[i] = (gray[i] << 16) | (gray[i] << 8) | gray[i];
			return new Rgb(w, h, out);
		}
		var argb = img.getRGB(0, 0, w, h, null, 0, w);
		for (int i = 0; i < argb.length; i++)
			argb[i] &= 0xFFFFFF;
		return new Rgb(w, h, argb);
	}

	private static Raster readRaster(byte[] jpeg) throws IOException {
		var readers = ImageIO.getImageReadersByFormatName("jpeg");
		IOException last = null;
		while (readers.hasNext()) {
			ImageReader reader = readers.next();
			try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(jpeg))) {
				reader.setInput(iis, true, true);
				return reader.readRaster(0, null);
			} catch (IOException | RuntimeException e) {
				last = e instanceof IOException io ? io : new IOException(e);
			} finally {
				reader.dispose();
			}
		}
		throw new IOException("Unable to decode JPEG tile" + (last == null ? "" : ": " + last.getMessage()), last);
	}

	private static int ycbcrToRgb(int y, int cb, int cr) {
		double r = y + 1.402 * (cr - 128);
		double g = y - 0.344136 * (cb - 128) - 0.714136 * (cr - 128);
		double b = y + 1.772 * (cb - 128);
		return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
	}

	private static int clamp(double v) {
		return (int) Math.max(0, Math.min(255, Math.round(v)));
	}

	private static int luma(int rgb) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return (299 * r + 587 * g + 114 * b + 500) / 1000;
	}

	private static byte[] toBytes(int[] samples) {
		var out = new byte[samples.length];
		for (int i = 0; i < out.length; i++)
			out[i] = (byte) samples[i];
		return out;
	}
}
