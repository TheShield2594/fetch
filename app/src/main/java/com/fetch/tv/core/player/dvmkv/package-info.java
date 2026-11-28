/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * App-level (AAR mode) vendored Matroska extractor.
 *
 * <p>This package is a self-contained copy of AndroidX Media3's Matroska extractor and its
 * supporting EBML readers, relocated out of {@code androidx.media3.extractor.mkv} so it can be
 * compiled into the app alongside the stock prebuilt Media3 AARs. It exposes a {@link
 * com.fetch.tv.core.player.dvmkv.MatroskaExtractor.DolbyVisionSampleTransformer} seam that the
 * app wires to the libdovi bridge to perform DV7 to DV8.1 conversion for MKV, whose RPU rides
 * in BlockAdditional and is otherwise discarded by the stock extractor.
 *
 * <p>Upstream base: the copy was taken from androidx/media after the 1.8.0 release (it carries
 * post-1.8.0 helpers, vendored where 1.8.0 lacks them, such as {@code DtsUtil.isSampleDtsHd}),
 * and it is compiled against Media3 1.8.0. The exact upstream commit was not recorded when it
 * was forked. {@code DvMkvMedia3VersionTest} fails when the Media3 version changes, so an
 * upgrade has to re-check this copy against the new upstream extractor and update the record.
 */
package com.fetch.tv.core.player.dvmkv;
