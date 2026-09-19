// Deterministic content source for the end-to-end tests.
// The harness replaces __FIXTURE_BASE__ with the local media server's base URL before installing.
const BASE = "__FIXTURE_BASE__";
const PLATFORM = "Fixture";
const VIDEO_PREFIX = "https://fixture.test/watch/";
const CHANNEL_PREFIX = "https://fixture.test/channel/";
let config = {};

// Videos 01-06 belong to Alpha, 07-15 to Beta. Home shows 01-08, recommendations are always 13-15.
const CHANNELS = {
    alpha: { name: "Alpha Channel", videos: [1, 2, 3, 4, 5, 6] },
    beta: { name: "Beta Channel", videos: [7, 8, 9, 10, 11, 12] }
};
const HOME = [1, 2, 3, 4, 5, 6, 7, 8];
const RECOMMENDED = [13, 14, 15];
const ALL = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];

function pad(n) { return n < 10 ? "0" + n : "" + n; }
function channelKeyOf(n) { return n <= 6 ? "alpha" : "beta"; }
function videoUrl(n) { return VIDEO_PREFIX + pad(n); }
function channelUrl(key) { return CHANNEL_PREFIX + key; }
function videoNumber(url) { return parseInt(url.substring(VIDEO_PREFIX.length), 10); }
function channelKey(url) { return url.substring(CHANNEL_PREFIX.length); }

function author(key) {
    return new PlatformAuthorLink(new PlatformID(PLATFORM, key, config.id), CHANNELS[key].name, channelUrl(key), BASE + "/thumb.png", 1000);
}

function videoObj(n) {
    return {
        id: new PlatformID(PLATFORM, "v" + pad(n), config.id),
        name: "Video " + pad(n),
        thumbnails: new Thumbnails([new Thumbnail(BASE + "/thumb.png", 180)]),
        author: author(channelKeyOf(n)),
        datetime: 1700000000 - n * 3600,
        url: videoUrl(n),
        shareUrl: videoUrl(n),
        duration: 60,
        viewCount: 1000 + n,
        isLive: false
    };
}

function video(n) { return new PlatformVideo(videoObj(n)); }

source.enable = function (conf, settings, savedState) { config = conf; };

//Content that points at another platform's video; the app renders it with its own item view
function nestedItem() {
    return new PlatformNestedMediaContent({
        id: new PlatformID(PLATFORM, "n01", config.id),
        name: "Nested 01",
        thumbnails: new Thumbnails([new Thumbnail(BASE + "/thumb.png", 180)]),
        author: author("alpha"),
        datetime: 1700000000,
        url: "https://fixture.test/nested/01",
        contentUrl: videoUrl(1),
        contentName: "Video 01",
        contentDescription: "A fixture video inside nested content",
        contentProvider: "Fixture",
        contentThumbnails: new Thumbnails([new Thumbnail(BASE + "/thumb.png", 180)])
    });
}

source.getHome = function () {
    return new ContentPager(HOME.map(video).concat([nestedItem()]), false, {});
};

source.searchSuggestions = function (query) { return []; };

source.getSearchCapabilities = function () {
    return { types: [Type.Feed.Mixed], sorts: [], filters: [] };
};

source.search = function (query, type, order, filters) {
    const q = (query ?? "").toLowerCase();
    return new VideoPager(ALL.filter(n => ("video " + pad(n)).includes(q)).map(video), false, {});
};

source.searchChannels = function (query) { return new ChannelPager([], false, {}); };

source.isChannelUrl = function (url) { return url.startsWith(CHANNEL_PREFIX); };

source.getChannel = function (url) {
    const key = channelKey(url);
    return new PlatformChannel({
        id: new PlatformID(PLATFORM, key, config.id),
        name: CHANNELS[key].name,
        thumbnail: BASE + "/thumb.png",
        banner: BASE + "/thumb.png",
        subscribers: 1000,
        description: CHANNELS[key].name + " (fixture)",
        url: channelUrl(key),
        links: {}
    });
};

source.getChannelCapabilities = function () {
    return { types: [Type.Feed.Mixed], sorts: [Type.Order.Chronological], filters: [] };
};

source.getChannelContents = function (url, type, order, filters) {
    return new VideoPager(CHANNELS[channelKey(url)].videos.map(video), false, {});
};

source.isContentDetailsUrl = function (url) { return url.startsWith(VIDEO_PREFIX); };

source.getContentDetails = function (url) {
    const n = videoNumber(url);
    const obj = videoObj(n);
    obj.description = "Fixture video " + pad(n);
    obj.rating = new RatingLikes(10);
    obj.subtitles = [];
    //The app asks the details object for recommendations, not source.getContentRecommendations
    obj.getContentRecommendations = function () {
        return new ContentPager(RECOMMENDED.map(video), false, {});
    };
    obj.video = new VideoSourceDescriptor([
        new VideoUrlSource({
            url: BASE + "/media/" + pad(n) + ".mp4",
            width: 160,
            height: 90,
            container: "video/mp4",
            codec: "avc1.42E01E",
            name: "90p",
            bitrate: 60000,
            duration: 60
        })
    ]);
    return new PlatformVideoDetails(obj);
};

source.getContentRecommendations = function (url) {
    return new VideoPager(RECOMMENDED.map(video), false, {});
};

source.getComments = function (url) { return new CommentPager([], false, {}); };
