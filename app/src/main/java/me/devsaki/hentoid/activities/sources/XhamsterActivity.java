package me.devsaki.hentoid.activities.sources;

import me.devsaki.hentoid.enums.Site;

public class XhamsterActivity extends BaseWebActivity {

    private static final String DOMAIN_FILTER = "xhamster.com";
    private static final String[] GALLERY_FILTER = {"/gallery/", "nhentai.net/search/\\?q=[0-9]+$"};
    private static final String[] DIRTY_ELEMENTS = {"section.advertisement"};

    Site getStartSite() {
        return Site.XHAMSTER;
    }

    @Override
    boolean allowMixedContent() {
        return false;
    }

    @Override
    protected CustomWebViewClient getWebClient() {
        CustomWebViewClient client = new CustomWebViewClient(getStartSite(), GALLERY_FILTER, this);
        client.restrictTo(DOMAIN_FILTER);
        client.addDirtyElements(DIRTY_ELEMENTS);
        return client;
    }
}
