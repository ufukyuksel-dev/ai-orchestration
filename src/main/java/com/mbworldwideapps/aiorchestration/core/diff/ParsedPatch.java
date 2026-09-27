package com.mbworldwideapps.aiorchestration.core.diff;

import com.mbworldwideapps.aiorchestration.core.diff.ParsedFile;
import java.util.List;

public record ParsedPatch(List<ParsedFile> files) {

    public ParsedPatch {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public int fileCount() {
        return files.size();
    }
}
