/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.gradle.transform;

import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.internal.UncheckedException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Function;

public interface UnpackTransform extends TransformAction<UnpackTransform.Parameters> {

    interface Parameters extends TransformParameters {
        @Input
        @Optional
        String getTrimmedPrefixPattern();

        void setTrimmedPrefixPattern(String pattern);
    }

    @PathSensitive(PathSensitivity.NAME_ONLY)
    @InputArtifact
    Provider<FileSystemLocation> getArchiveFile();

    @Override
    default void transform(TransformOutputs outputs) {
        File archiveFile = getArchiveFile().get().getAsFile();
        File extractedDir = outputs.dir(archiveFile.getName());
        try {
            Logging.getLogger(UnpackTransform.class)
                .info("Unpacking " + archiveFile.getName() + " using " + getClass().getSimpleName() + ".");
            unpack(archiveFile, extractedDir);
        } catch (IOException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    void unpack(File archiveFile, File targetDir) throws IOException;

    default Function<String, Path> pathResolver() {
        String trimmedPrefixPattern = getParameters().getTrimmedPrefixPattern();
        return trimmedPrefixPattern != null ? (i) -> trimArchiveExtractPath(trimmedPrefixPattern, i) : (i) -> Paths.get(i);
    }

    /*
     * We want to be able to trim off certain prefixes when transforming archives.
     *
     * E.g We want to remove up to the and including the jdk-.* relative paths. That is a JDK archive is structured as:
     *   jdk-12.0.1/
     *   jdk-12.0.1/Contents
     *   ...
     *
     * and we want to remove the leading jdk-12.0.1. Note however that there could also be a leading ./ as in
     *   ./
     *   ./jdk-12.0.1/
     *   ./jdk-12.0.1/Contents
     *
     * so we account for this and search the path components until we find the jdk-12.0.1, and strip the leading components.
     */
    static Path trimArchiveExtractPath(String ignoredPattern, String relativePath) {
        final Path entryName = Paths.get(relativePath);
        int index = 0;
        for (; index < entryName.getNameCount(); index++) {
            if (entryName.getName(index).toString().matches(ignoredPattern)) {
                break;
            }
        }
        if (index + 1 >= entryName.getNameCount()) {
            // this happens on the top-level directories in the archive, which we are removing
            return null;
        }
        // finally remove the top-level directories from the output path
        return entryName.subpath(index + 1, entryName.getNameCount());
    }
}
