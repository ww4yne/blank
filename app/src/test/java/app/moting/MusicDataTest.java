package app.moting;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class MusicDataTest {
    @Test public void recommendationGroupsPreserveTitlesSharedContentAndContentPagination() throws Exception {
        MusicData.Page result = MusicData.recommendations(new JSONObject("""
                {"data":[
                  {"id":"r1","type":"personal-recommendation",
                   "attributes":{"title":{"stringForDisplay":"专属精选"},"reason":{"stringForDisplay":"根据聆听记录"}},
                   "relationships":{"contents":{"data":[{"id":"mix","type":"playlists"}],
                     "next":"/v1/me/recommendations/r1/contents?offset=10"}}},
                  {"id":"r2","type":"personal-recommendation",
                   "attributes":{"title":{"stringForDisplay":"重温喜爱"}},
                   "relationships":{"contents":{"data":[{"id":"mix","type":"playlists"}]}}}],
                 "included":[{"id":"mix","type":"playlists","attributes":{"name":"爱不释耳","playParams":{"id":"mix"}}}],
                 "next":"/v1/me/recommendations?offset=10"}
                """));
        assertEquals(2, result.items().size());
        assertTrue(result.items().get(0).group());
        assertEquals("专属精选", result.items().get(0).title());
        assertEquals("根据聆听记录", result.items().get(0).subtitle());
        assertEquals(2, result.groups().size());
        assertEquals("mix", result.groups().get(0).items().get(0).id());
        assertEquals("mix", result.groups().get(1).items().get(0).id());
        assertEquals("/v1/me/recommendations/r1/contents?offset=10", result.groups().get(0).next());
        assertEquals("/v1/me/recommendations?offset=10", result.next());
    }

    @Test public void nestedRecommendationGroupsResolveIncludedReferencesWithoutFlattening() throws Exception {
        MusicData.Page result = MusicData.recommendations(new JSONObject("""
                {"data":[{"id":"parent","type":"personal-recommendation",
                  "attributes":{"title":{"stringForDisplay":"为你精选"}},
                  "relationships":{"contents":{"data":[{"id":"child","type":"personal-recommendation"}]}}}],
                 "included":[{"id":"child","type":"personal-recommendation",
                  "attributes":{"title":{"stringForDisplay":"新发现"}},
                  "relationships":{"contents":{"data":[{"id":"album","type":"albums",
                    "attributes":{"name":"新专辑"}}]}}}]}
                """));
        assertEquals(1, result.items().size());
        assertEquals("parent", result.items().get(0).id());
        MusicData.Group parent = result.groups().stream().filter(group -> group.id().equals("parent")).findFirst().orElseThrow();
        assertTrue(parent.items().get(0).group());
        assertEquals("新发现", parent.items().get(0).title());
        assertEquals("新专辑", result.groups().get(0).items().get(0).title());
    }

    @Test public void stationOnlyGroupsAreNotInventedAsPlayableRecommendations() throws Exception {
        MusicData.Page result = MusicData.recommendations(new JSONObject("""
                {"data":[{"id":"radio","type":"personal-recommendation",
                 "relationships":{"contents":{"data":[{"id":"station","type":"stations","attributes":{"name":"Radio"}}]}}}]}
                """));
        assertTrue(result.items().isEmpty());
        assertTrue(result.groups().isEmpty());
    }

    @Test public void recommendationReferenceCyclesFailExplicitly() {
        assertThrows(org.json.JSONException.class, () -> MusicData.recommendations(new JSONObject("""
                {"data":[{"id":"loop","type":"personal-recommendation",
                 "relationships":{"contents":{"data":[{"id":"loop","type":"personal-recommendation"}]}}}]}
                """)));
    }

    @Test public void mixedContentPagesKeepOrdinaryItemsAndNestedRecommendationMenus() throws Exception {
        MusicData.Page result = MusicData.recommendations(new JSONObject("""
                {"data":[{"id":"mix","type":"playlists","attributes":{"name":"推荐歌单"}},
                 {"id":"child","type":"personal-recommendation","attributes":{"title":{"stringForDisplay":"更多发现"}},
                  "relationships":{"contents":{"data":[{"id":"album","type":"albums","attributes":{"name":"新专辑"}}]}}}]}
                """));
        assertEquals(2, result.items().size());
        assertTrue(result.items().get(0).playlist());
        assertTrue(result.items().get(1).group());
        assertEquals("更多发现", result.items().get(1).title());
        assertEquals("新专辑", result.groups().get(0).items().get(0).title());
    }

    @Test public void recommendationReferencesResolveWithoutMixingAlbumTracks() throws Exception {
        JSONObject response = new JSONObject("""
                {"data":[{"id":"r","type":"personal-recommendation",
                 "relationships":{"contents":{"data":[
                  {"id":"p","type":"playlists"},{"id":"s","type":"stations"},{"id":"a","type":"albums"}]}}}],
                 "included":[
                  {"id":"p","type":"playlists","attributes":{"name":"爱不释耳","playParams":{"id":"p"}},
                   "relationships":{"tracks":{"data":[{"id":"unrelated","type":"songs","attributes":{"name":"Not a recommendation"}}]}}},
                  {"id":"a","type":"albums","attributes":{"name":"Album","artistName":"Artist","playParams":{"id":"a"}}},
                  {"id":"s","type":"stations","attributes":{"name":"My Station"}}]}
                """);
        MusicData.Page page = MusicData.parse(response);
        assertEquals(2, page.items().size());
        assertEquals("爱不释耳", page.items().get(0).title());
        assertTrue(page.items().get(0).playlist());
        assertEquals("Album", page.items().get(1).title());
    }

    @Test public void searchFlattensCategoriesAndDeduplicatesResources() throws Exception {
        JSONObject response = new JSONObject("""
                {"results":{"songs":{"data":[
                  {"id":"1","type":"songs","attributes":{"name":"Track","artistName":"Artist","playParams":{"id":"1"}}},
                  {"id":"1","type":"songs","attributes":{"name":"Track","playParams":{"id":"1"}}}]},
                 "playlists":{"data":[{"id":"2","type":"playlists","attributes":{"name":"Playlist"}}]}}}
                """);
        MusicData.Page result = MusicData.parse(response);
        assertEquals(2, result.items().size());
        assertEquals(1, MusicData.songs(result.items()).size());
        assertEquals("1", MusicData.songs(result.items()).get(0).catalogId());
    }

    @Test public void libraryCatalogIdsAndPaginationArePreserved() throws Exception {
        MusicData.Page result = MusicData.parse(new JSONObject("""
                {"data":[{"id":"i.abc","type":"library-songs","attributes":
                  {"name":"Library Track","playParams":{"id":"i.abc","catalogId":"123"}}}],
                 "next":"/v1/me/library/songs?offset=25"}
                """));
        assertEquals("123", result.items().get(0).catalogId());
        assertTrue(result.items().get(0).library());
        assertEquals("/v1/me/library/songs?offset=25", result.next());
    }

    @Test public void missingPlaybackParametersAreNotInvented() throws Exception {
        MusicData.Page result = MusicData.parse(new JSONObject("""
                {"data":[{"id":"x","type":"songs","attributes":{"name":"Unavailable"}}]}
                """));
        assertEquals("", result.items().get(0).catalogId());
    }

    @Test public void emptyRecommendationsAreValid() throws Exception {
        assertTrue(MusicData.parse(new JSONObject("{\"data\":[]}")).items().isEmpty());
    }

    @Test public void searchPaginationSurvivesFollowingAnApiNextLink() throws Exception {
        MusicData.Page page = MusicData.parse(new JSONObject("""
                {"results":{"songs":{"data":[],"next":"/v1/catalog/us/search?types=songs&offset=25"}}}
                """));
        assertEquals("/v1/catalog/us/search?types=songs&offset=25", page.next());
    }

    @Test public void playlistsPreserveIntentionalRepeatedTracks() throws Exception {
        MusicData.Page page = MusicData.parseTracks(new JSONObject("""
                {"data":[{"id":"1","type":"songs","attributes":{"name":"Repeat","playParams":{"id":"1"}}},
                         {"id":"1","type":"songs","attributes":{"name":"Repeat","playParams":{"id":"1"}}}]}
                """));
        assertEquals(2, page.items().size());
    }
}
