package me.devsaki.hentoid.activities.sources;

import me.devsaki.hentoid.enums.Site;

public class FapalityActivity extends BaseWebActivity {

    private static final String DOMAIN_FILTER = "fapality.com";
    private static final String[] GALLERY_FILTER = {"fapality.com/.*/[0-9]+/.*/$"};
    private static final String[] DIRTY_ELEMENTS = {".had"};

    Site getStartSite() {
        return Site.FAPALITY;
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
