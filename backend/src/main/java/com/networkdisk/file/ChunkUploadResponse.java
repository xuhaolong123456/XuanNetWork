package com.networkdisk.file;

import java.util.List;

public record ChunkUploadResponse(String uploadId, int partNumber, MergeFlag mergeFlag,
                                  List<Integer> finishedPartList) { }
