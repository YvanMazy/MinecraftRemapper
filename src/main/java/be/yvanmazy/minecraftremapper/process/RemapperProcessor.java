/*
 * MIT License
 *
 * Copyright (c) 2024 Yvan Mazy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package be.yvanmazy.minecraftremapper.process;

import be.yvanmazy.minecraftremapper.DirectionType;
import be.yvanmazy.minecraftremapper.http.exception.RequestHttpException;
import be.yvanmazy.minecraftremapper.process.exception.ProcessingException;
import be.yvanmazy.minecraftremapper.setting.PreparationSettings;
import be.yvanmazy.minecraftremapper.util.FileUtil;
import be.yvanmazy.minecraftremapper.util.HashUtil;
import com.google.gson.JsonObject;
import net.md_5.specialsource.Jar;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;

public class RemapperProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemapperProcessor.class);

    private final PreparationSettings config;
    private final Path root;

    private JsonObject downloadJson;

    public RemapperProcessor(final @NotNull PreparationSettings config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.root = Path.of(config.outputDirectory(), this.config.version().id() + config.target().name().toLowerCase());
    }

    public void process() throws ProcessingException {
        this.createOutputDirectory();
        this.downloadJson = this.downloadVersionJson();

        final DownloadResult jarResult = this.downloadJar();
        // Unpack server version jar
        if (this.config.target() == DirectionType.SERVER) {
            if (jarResult.skipped()) {
                LOGGER.info("SKIP --> Unpack server is already done.");
            } else {
                this.unpackServerJar(jarResult.path());
            }
        }
        final Path mappingPath = this.downloadMapping();
        if (this.config.remap()) {
            final Path remapPath = this.remapJar(jarResult, mappingPath, this.getRemappedJarPath());
            if (this.config.decompile()) {
                LOGGER.info("Decompiling...");
                final Path path = remapPath.resolveSibling("decompiled");
                try {
                    FileUtil.recursiveDelete(path);
                } catch (final IOException e) {
                    LOGGER.error("Failed to delete directory with decompiled files, continue to decompile...", e);
                }
                Decompiler.builder().inputs(remapPath.toFile()).output(new DirectoryResultSaver(path.toFile())).build().decompile();
            }
        }
    }

    public @NotNull Path getVersionJarPath() {
        return this.root.resolve(this.config.version().id() + ".jar");
    }

    public @NotNull Path getMappingPath() {
        return this.root.resolve(this.config.version().id() + ".map");
    }

    public @NotNull Path getRemappedJarPath() {
        return this.root.resolve("remapped-" + this.config.version().id() + ".jar");
    }

    public @NotNull Path getVersionMetaPath() {
        return this.root.resolve(this.config.version().id() + ".json");
    }

    private void createOutputDirectory() throws ProcessingException {
        if (!Files.isDirectory(this.root)) {
            try {
                Files.createDirectories(this.root);
            } catch (final IOException e) {
                throw new ProcessingException("Failed to create output directory", e);
            }
        }
    }

    private JsonObject downloadVersionJson() throws ProcessingException {
        final Path path = this.getVersionMetaPath();
        String json;
        if (Files.exists(path)) {
            try {
                json = Files.readString(path);
                final JsonObject downloads = this.parseDownloads(json);
                if (downloads != null) {
                    return downloads;
                }
            } catch (final Exception ignored) {
            }
        }
        final String url = this.config.version().url();
        try {
            json = this.config.httpClient().getString(url);
        } catch (final RequestHttpException e) {
            throw new ProcessingException("Failed to download version metadata", e);
        }
        try {
            Files.writeString(path, json);
        } catch (final IOException e) {
            LOGGER.error("Failed to save version metadata", e);
        }
        return this.parseDownloads(json);
    }

    private DownloadResult downloadJar() throws ProcessingException {
        return this.download("Version jar", this.config.getTargetKey(), this.getVersionJarPath());
    }

    private Path downloadMapping() throws ProcessingException {
        return this.download("Version mapping", this.config.getTargetKey() + "_mappings", this.getMappingPath()).path();
    }

    private void unpackServerJar(final Path path) throws ProcessingException {
        LOGGER.info("Unpack server jar...");
        final String id = this.config.version().id();
        try (final FileSystem fs = FileSystems.newFileSystem(path, (ClassLoader) null)) {
            final Path source = fs.getPath("META-INF/versions/" + id + "/server-" + id + ".jar");
            if (Files.notExists(source)) {
                return;
            }
            final Path destination = path.getParent().resolve(id + ".jar");
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            throw new ProcessingException("Failed to unpack server jar", e);
        }
    }

    private Path remapJar(final DownloadResult jarResult, final Path mappingPath, final Path outPath) throws ProcessingException {
        if (jarResult.skipped() && FileUtil.isValidJar(outPath)) {
            LOGGER.info("SKIP --> Remapping is already done.");
            return outPath;
        }
        LOGGER.info("Load mappings...");
        final JarMapping jarMapping = new JarMapping();
        try {
            jarMapping.loadMappings(mappingPath.toFile());
        } catch (final IOException e) {
            throw new ProcessingException("Failed to load mapping", e);
        }
        final JarRemapper jarRemapper = new JarRemapper(jarMapping);
        LOGGER.info("Remapping...");
        try {
            jarRemapper.remapJar(Jar.init(jarResult.path().toFile()), outPath.toFile());
        } catch (final IOException e) {
            throw new ProcessingException("Failed to remap jar", e);
        }
        return outPath;
    }

    private DownloadResult download(final String display, final String jsonKey, final Path outPath) throws ProcessingException {
        final JsonObject base = this.downloadJson.getAsJsonObject(jsonKey);
        final String sha1 = base.get("sha1").getAsString();

        try {
            if (this.isAlreadyDownloaded(outPath, sha1)) {
                LOGGER.info("SKIP --> {} is already downloaded.", display);
                return new DownloadResult(outPath, true);
            }
        } catch (final IOException e) {
            throw new ProcessingException("Failed to check sha1 file", e);
        }

        LOGGER.info("Downloading {}...", display);
        final long start = System.currentTimeMillis();

        final String fileUrl = base.get("url").getAsString();
        final byte[] fileContent;
        try {
            fileContent = this.config.httpClient().getBytes(fileUrl);
        } catch (final RequestHttpException e) {
            throw new ProcessingException("Failed to download file data", e);
        }
        try {
            Files.write(outPath, fileContent);
        } catch (final IOException e) {
            throw new ProcessingException("Failed to write file", e);
        }
        if (sha1 != null) {
            try {
                if (!sha1.equals(HashUtil.hash(outPath))) {
                    throw new ProcessingException("Checksum failed for '" + display + "'");
                }
            } catch (final IOException e) {
                throw new ProcessingException("Failed to checksum for '" + display + "'", e);
            }
            try {
                Files.writeString(this.toHashPath(outPath), sha1);
            } catch (final IOException e) {
                throw new ProcessingException("Failed to write sha1 file", e);
            }
        }

        LOGGER.info("{} is downloaded in {}ms", display, System.currentTimeMillis() - start);
        return new DownloadResult(outPath, false);
    }

    private JsonObject parseDownloads(final String json) {
        return this.config.gson().fromJson(json, JsonObject.class).getAsJsonObject("downloads");
    }

    private boolean isAlreadyDownloaded(final Path path, final String sha1) throws IOException {
        if (Files.notExists(path)) {
            return false;
        }
        if (path.getFileName().toString().endsWith(".jar") && !FileUtil.isValidJar(path)) {
            return false;
        }
        if (sha1 != null) {
            if (!sha1.equals(HashUtil.hash(path))) {
                return false;
            }
            final Path hashFile = this.toHashPath(path);
            if (Files.exists(hashFile)) {
                return Files.readString(hashFile).equals(sha1);
            }
            return false;
        }
        return true;
    }

    private Path toHashPath(final @NotNull Path path) {
        return path.toAbsolutePath().resolveSibling(path.getFileName().toString() + ".sha1");
    }

}