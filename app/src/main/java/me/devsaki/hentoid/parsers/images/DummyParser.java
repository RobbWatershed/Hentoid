package me.devsaki.hentoid.parsers.images;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.ImageFile;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.parsers.ParseHelper;

public class DummyParser implements ImageListParser {
    @Override
    public List<ImageFile> parseImageList(@NonNull Content content) {
        return (null == content.getImageFiles()) ? new ArrayList<>() : new ArrayList<>(content.getImageFiles());
    }

    @Override
    public ImageFile parseBackupUrl(@NonNull String url, int order, int maxPages) {
        return ParseHelper.urlToImageFile(url, order, maxPages, StatusContent.SAVED);
    }
}
