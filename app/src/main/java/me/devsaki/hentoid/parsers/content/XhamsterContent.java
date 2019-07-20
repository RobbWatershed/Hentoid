package me.devsaki.hentoid.parsers.content;

import androidx.annotation.NonNull;

import org.jsoup.nodes.Element;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.enums.AttributeType;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.parsers.ParseHelper;
import me.devsaki.hentoid.util.AttributeMap;
import pl.droidsonroids.jspoon.annotation.Selector;
import timber.log.Timber;

public class XhamsterContent implements ContentParser {

    private String GALLERY_FOLDER = "/photos/gallery/";

    @Selector(value = "head meta[name='twitter:url']", attr = "content", defValue = "")
    private String galleryUrl;
    @Selector(value = "img.thumb", attr = "src")
    private List<String> thumbs;
    @Selector("h1.page-title")
    private String title;
    @Selector("head title")
    private String headTitle;
    @Selector(value = ".categories_of_pictures .categories-container__item")
    private List<Element> tags;


    public Content toContent(@NonNull String url) {
        Content result = new Content();

        result.setSite(Site.XHAMSTER);

        String theUrl = galleryUrl.isEmpty() ? url : galleryUrl;
        int galleryLocation = theUrl.indexOf(GALLERY_FOLDER) + GALLERY_FOLDER.length();
        result.setUrl(theUrl.substring(galleryLocation));
        result.setCoverImageUrl(thumbs.isEmpty() ? "" : thumbs.get(0));
        result.setTitle(title);

        Pattern pattern = Pattern.compile(".* - (\\d+) .* - .*"); // e.g. "Big bewbs - 50 Pics - xHamster.com"
        Matcher matcher = pattern.matcher(headTitle);

        Timber.d("Match found? %s", matcher.find());

        if (matcher.groupCount() > 0) {
            String results = matcher.group(1);
            result.setQtyPages(Integer.parseInt(results));
        }

        AttributeMap attributes = new AttributeMap();

        ParseHelper.parseAttributes(attributes, AttributeType.TAG, tags, true, Site.XHAMSTER);

        result.addAttributes(attributes);

        result.populateAuthor();
        result.setStatus(StatusContent.SAVED);

        return result;
    }
}
