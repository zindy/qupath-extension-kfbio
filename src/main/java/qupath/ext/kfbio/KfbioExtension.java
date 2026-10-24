package qupath.ext.kfbio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

import java.util.ResourceBundle;

/**
 * QuPath extension adding support for KFBio whole slide images ({@code .kfb} and {@code .kfbf}).
 * <p>
 * The actual work is done by {@link KfbImageServerBuilder}, which QuPath discovers through
 * {@code META-INF/services/qupath.lib.images.servers.ImageServerBuilder}. This class exists so that the extension
 * is listed under 'Extensions &gt; Installed extensions'. In contrast to the Bio-Formats based MCD extension,
 * there is no reader registration order to worry about: the builder is found by QuPath's normal service loading.
 */
public class KfbioExtension implements QuPathExtension {

	private static final Logger logger = LoggerFactory.getLogger(KfbioExtension.class);

	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.kfbio.ui.strings");

	private static final String EXTENSION_NAME = resources.getString("name");

	private static final String EXTENSION_DESCRIPTION = resources.getString("description");

	/**
	 * QuPath version that the extension is designed to work with.
	 */
	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");

	private boolean isInstalled = false;

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;
		logger.info("{} installed: .kfb and .kfbf images can now be opened", getName());
	}

	@Override
	public String getName() {
		return EXTENSION_NAME;
	}

	@Override
	public String getDescription() {
		return EXTENSION_DESCRIPTION;
	}

	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}
}
