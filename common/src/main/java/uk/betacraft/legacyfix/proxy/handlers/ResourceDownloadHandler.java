package uk.betacraft.legacyfix.proxy.handlers;

import uk.betacraft.legacyfix.Logger;
import uk.betacraft.legacyfix.proxy.GameArgs;
import uk.betacraft.legacyfix.proxy.assets.AssetUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves individual legacy resource files requested from the S3 buckets.
 *
 * Question: after ResourceIndexHandler hands the game a locally generated index,
 * the game downloads every entry from
 * https://s3.amazonaws.com/MinecraftDownload/resources/&lt;key&gt; (or
 * .../MinecraftResources/&lt;key&gt; for Beta). Those objects no longer exist on S3,
 * so without this handler every sound/music file produces a 404 stack trace.
 */
@SuppressWarnings("all")
public class ResourceDownloadHandler extends HandlerBase {
    private static final Pattern DOWNLOAD_PATTERN = Pattern.compile(
        "https?:\\/\\/(s3\\.amazonaws\\.com)\\/(?:MinecraftDownload\\/resources|MinecraftResources)\\/(.+)"
    );

    private int contentLength = -1;

    public ResourceDownloadHandler(URL u, Pattern patternUsed) {
        super(u, patternUsed);
    }

    public InputStream getInputStream() throws IOException {
        String urlString = this.getURLString();
        Matcher matcher = urlString == null ? null : DOWNLOAD_PATTERN.matcher(urlString);

        if (matcher == null || !matcher.matches()) {
            return this.emptyStream();
        }

        String key = matcher.group(2);

        AssetUtils.AssetObject asset = AssetUtils.getAssetByKey(key);
        if (asset != null) {
            File file = new File(asset.path);
            if (file.isFile()) {
                this.contentLength = (int) file.length();
                this.inputStream = new FileInputStream(file);
                return this.inputStream;
            }
        }

        File local = new File(new File(GameArgs.getGameDir(), "resources"), key.replace('/', File.separatorChar));
        if (local.isFile()) {
            this.contentLength = (int) local.length();
            this.inputStream = new FileInputStream(local);
            return this.inputStream;
        }

        Logger.debug("ResourceDownloadHandler", "Missing legacy resource, serving empty stream: " + key);
        return this.emptyStream();
    }

    private InputStream emptyStream() {
        this.contentLength = 0;
        this.inputStream = new ByteArrayInputStream(new byte[0]);
        return this.inputStream;
    }

    public int getContentLength() {
        return this.contentLength;
    }

    public static List<Pattern> regexPatterns() {
        return Arrays.asList(DOWNLOAD_PATTERN);
    }
}
