package centuryroad.history.wikipedia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class WikimediaUrlsUnitTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://it.wikipedia.org/wiki/Roma",
            "https://en.wikipedia.org/wiki/Rome",
            "https://upload.wikimedia.org/wikipedia/commons/a/ab/X.jpg",
            "https://commons.wikimedia.org/wiki/File:X.jpg"})
    void httpsLinksOnWikipediaAndWikimediaHostsAreAccepted(String url) {
        assertThat(WikimediaUrls.isWikimediaHttps(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://it.wikipedia.org/wiki/Roma",
            "javascript:alert(1)",
            "https://evil.example/wiki/Roma",
            "https://it.wikipedia.org.evil.example/wiki/Roma",
            "https://evilwikipedia.org/wiki/Roma",
            "//it.wikipedia.org/wiki/Roma",
            "not a url at all",
            ""})
    void everythingElseIsRefused(String url) {
        assertThat(WikimediaUrls.isWikimediaHttps(url)).isFalse();
    }

    @Test
    void aCommonsThumbnailPointsBackAtItsFilePage() {
        assertThat(WikimediaUrls.commonsFilePage(
                "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Papa_Esempio.jpg/330px-Papa_Esempio.jpg"))
                .contains("https://commons.wikimedia.org/wiki/File:Papa_Esempio.jpg");
    }

    @Test
    void aCommonsOriginalDoesToo() {
        assertThat(WikimediaUrls.commonsFilePage("https://upload.wikimedia.org/wikipedia/commons/a/ab/Papa_Esempio.jpg"))
                .contains("https://commons.wikimedia.org/wiki/File:Papa_Esempio.jpg");
    }

    @Test
    void thePercentEncodingOfTheFileNameSurvives_andTheQueryStringIsIgnored() {
        assertThat(WikimediaUrls.commonsFilePage(
                "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c3/Citt%C3%A0.jpg/330px-Citt%C3%A0.jpg"
                        + "?utm_source=it.wikipedia.org&utm_campaign=api"))
                .contains("https://commons.wikimedia.org/wiki/File:Citt%C3%A0.jpg");
    }

    @Test
    void aCommonsThumbnailServedFromTheThumbHostPointsBackAtItsFilePageToo() {
        // What the feed sends today for most images: the same Commons path, another host.
        assertThat(WikimediaUrls.commonsFilePage(
                "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b4/Romano_Prodi_2024_%28cropped%29.jpg"
                        + "/330px-Romano_Prodi_2024_%28cropped%29.jpg?utm_source=it.wikipedia.org&utm_campaign=api"))
                .contains("https://commons.wikimedia.org/wiki/File:Romano_Prodi_2024_%28cropped%29.jpg");
        assertThat(WikimediaUrls.commonsFilePage(
                "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b7/Flag_of_Europe.svg/langit-330px-Flag_of_Europe.svg.png"))
                .contains("https://commons.wikimedia.org/wiki/File:Flag_of_Europe.svg");
    }

    @Test
    void anImageOnTheThumbHostFromASingleWikiIsStillNotOne() {
        assertThat(WikimediaUrls.commonsFilePage(
                "https://thumb.wikimedia.org/wikipedia/it/thumb/5/5b/Locale.jpg/330px-Locale.jpg")).isEmpty();
    }

    @Test
    void anImageUploadedToASingleWikiIsNotOneBecauseNothingSaysItIsFree() {
        assertThat(WikimediaUrls.commonsFilePage(
                "https://upload.wikimedia.org/wikipedia/it/thumb/5/5b/Locale.jpg/330px-Locale.jpg")).isEmpty();
        assertThat(WikimediaUrls.commonsFilePage(
                "https://upload.wikimedia.org/wikipedia/en/5/5b/Locale.jpg")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example/wikipedia/commons/a/ab/X.jpg",
            "https://thumb.wikimedia.org.evil.example/wikipedia/commons/thumb/a/ab/X.jpg/330px-X.jpg",
            "https://evilthumb.wikimedia.org/wikipedia/commons/thumb/a/ab/X.jpg/330px-X.jpg",
            "https://upload.wikimedia.org/somewhere/else.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/zz/ab/X.jpg",
            "not a url at all",
            ""})
    void nothingButARealCommonsPathQualifies(String url) {
        assertThat(WikimediaUrls.commonsFilePage(url)).isEmpty();
    }
}
