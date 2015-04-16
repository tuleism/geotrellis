package geotrellis.raster.io.geotiff.tags

import monocle.macros.Lenses

@Lenses("_")
case class BasicTags(
  bitsPerSample: Option[Array[Int]] = None,
  colorMap: Seq[(Short, Short, Short)] = Seq(),
  imageLength: Int = 0,
  imageWidth: Int = 0,
  compression: Int = 1,
  photometricInterp: Int = -1,
  resolutionUnit: Option[Int] = None,
  rowsPerStrip: Long = (1 << 31) - 1,
  samplesPerPixel: Int = 1,
  stripByteCounts: Option[Array[Int]] = None,
  stripOffsets: Option[Array[Int]] = None,
  xResolution: Option[(Long, Long)] = None,
  yResolution: Option[(Long, Long)] = None
)
