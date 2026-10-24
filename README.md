# qupath-extension-kfbio

QuPath extension for opening [KFBio](https://www.kfbio.cn) whole slide images directly:
`.kfb` (brightfield) and `.kfbf` (fluorescence). No conversion step required.

> **Status: untested on real slides.** This was developed without access to any real
> `.kfb` / `.kfbf` file. It has only been tested against synthetic files written to the
> layout described below, which shows the code does what it was designed to do, not that
> the format was understood correctly. Bug reports with real-file failures are the most
> useful contribution at this stage.

## Requirements

QuPath 0.7.0 or later.

## Installation

Download the extension jar from the releases page and drop it onto the QuPath window
(or copy it into your QuPath extensions directory), then restart QuPath. Open `.kfb` and
`.kfbf` files as you would any other image.

## What it does

- Brightfield slides are served as RGB images, fluorescence slides as 8-bit multichannel
  images using the channel names and colours recorded in the file when present.
- The tile pyramid in the file is used directly. Pyramid levels that contain no tiles
  are not exposed. Regions without tiles are white (brightfield) or zero (fluorescence).
- Label, macro and thumbnail images are exposed as associated images when found.
- Pixel size and magnification are taken from the file header.

## Acknowledgements and attribution

**The knowledge of the file format comes entirely from
[kfb2zarr](https://github.com/camlloyd/kfb2zarr) by Cameron Lloyd** (MIT OR Apache-2.0,
DOI [10.5281/zenodo.20101668](https://doi.org/10.5281/zenodo.20101668)), a Rust converter
from KFBio slides to OME-Zarr. The container parsing in `KfbFile.java` (header fields,
tile index records, pyramid derivation, channel metadata, associated image discovery) is a
Java port of kfb2zarr's logic, and the synthetic test files in `SyntheticKfb.java` follow
the layout of kfb2zarr's own test helpers. This extension would not exist without that
reverse-engineering work. If you use this extension in published work, please also cite
kfb2zarr using the citation information in its repository.

Sample data used to develop kfb2zarr was kindly provided by KFBio. Neither kfb2zarr nor
this extension is an official product of KFBio, and the format is not documented by the
vendor as far as we know: everything here is "as understood by kfb2zarr".

The extension is built on [QuPath](https://qupath.github.io/) (Bankhead et al., 2017,
*Scientific Reports* 7:16878, <https://doi.org/10.1038/s41598-017-17204-5>) and follows the
[QuPath extension template](https://github.com/qupath/qupath-extension-template).

## Building

```bash
gradlew build
```

The jar is written to `build/libs`.

## License

Licensed under either of

 * Apache License, Version 2.0
   ([LICENSE-APACHE](LICENSE-APACHE) or <http://www.apache.org/licenses/LICENSE-2.0>)
 * MIT license
   ([LICENSE-MIT](LICENSE-MIT) or <http://opensource.org/licenses/MIT>)

at your option.

### Contribution

Unless you explicitly state otherwise, any contribution intentionally submitted for
inclusion in the work by you, as defined in the Apache-2.0 license, shall be dual licensed
as above, without any additional terms or conditions.
