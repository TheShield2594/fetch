package com.fetch.tv.core.player.avi;

import androidx.annotation.OptIn;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;

/** The OpenDML extended header ('dmlh'): the frame count of the whole file, not only the first RIFF. */
@OptIn(markerClass = UnstableApi.class)
/* package */ final class OpenDmlHeaderChunk implements AviChunk {

  public static OpenDmlHeaderChunk parseFrom(ParsableByteArray body) {
    return new OpenDmlHeaderChunk(body.bytesLeft() >= 4 ? body.readLittleEndianUnsignedInt() : 0);
  }

  public final long totalFrames;

  private OpenDmlHeaderChunk(long totalFrames) {
    this.totalFrames = totalFrames;
  }

  @Override
  public int getType() {
    return AviExtractor.FOURCC_dmlh;
  }
}
