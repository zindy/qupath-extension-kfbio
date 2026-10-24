package qupath.ext.kfbio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Builder that lets QuPath open KFBio {@code .kfb} and {@code .kfbf} files with {@link KfbImageServer}.
 * <p>
 * Registered through {@code META-INF/services/qupath.lib.images.servers.ImageServerBuilder}.
 */
public class KfbImageServerBuilder implements ImageServerBuilder<BufferedImage> {

	private static final Logger logger = LoggerFactory.getLogger(KfbImageServerBuilder.class);

	@Override
	public UriImageSupport<BufferedImage> checkImageSupport(URI uri, String... args) {
		float supportLevel = supportLevel(uri);
		return UriImageSupport.createInstance(this.getClass(), supportLevel,
				DefaultImageServerBuilder.createInstance(this.getClass(), uri, args));
	}

	private float supportLevel(URI uri) {
		Path path = GeneralTools.toPath(uri);
		if (path == null)
			return 0;
		String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
		if (!name.endsWith(".kfb") && !name.endsWith(".kfbf"))
			return 0;
		if (!Files.isRegularFile(path) || !KfbFile.hasKfbMagic(path))
			return 0;
		// Nothing else can read this format, so there is no competition to worry about
		return 4f;
	}

	@Override
	public ImageServer<BufferedImage> buildServer(URI uri, String... args) {
		try {
			return new KfbImageServer(uri, args);
		} catch (Exception e) {
			logger.error("Unable to open {} as a KFBio image: {}", uri, e.getMessage(), e);
			return null;
		}
	}

	@Override
	public String getName() {
		return "KFBio builder";
	}

	@Override
	public String getDescription() {
		return "Provides access to KFBio whole slide images (.kfb brightfield, .kfbf fluorescence)";
	}

	@Override
	public Class<BufferedImage> getImageType() {
		return BufferedImage.class;
	}

	@Override
	public boolean matchClassName(String... classNames) {
		for (var className : classNames) {
			if (this.getClass().getName().equals(className) ||
					this.getClass().getSimpleName().equals(className) ||
					KfbImageServer.class.getName().equals(className) ||
					KfbImageServer.class.getSimpleName().equals(className) ||
					"kfbio".equalsIgnoreCase(className) ||
					"kfb".equalsIgnoreCase(className))
				return true;
		}
		return false;
	}
}
