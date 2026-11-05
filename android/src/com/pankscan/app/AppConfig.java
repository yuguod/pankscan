package com.pankscan.app;

/**
 * 一处集中配置：自动更新从哪里取。
 *
 * 只改这一行就能换仓库——`publish.sh` 也用 grep 从本文件读 OWNER/REPO，
 * 所以脚本和 App 不会各写一份地址而对不上。
 */
final class AppConfig {

    /** GitHub 用户名 / 组织名 */
    static final String OWNER = "yuguod";

    /** 仓库名 */
    static final String REPO = "pankscan";

    /** 分支名，update.json 放在这个分支的根目录 */
    static final String BRANCH = "main";

    /**
     * 版本清单：raw.githubusercontent.com。
     * 不消耗 API 配额、不需要令牌、不会被匿名请求限速；但国内直连常常解析不了。
     */
    static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/" + OWNER + "/" + REPO + "/" + BRANCH + "/update.json";

    /**
     * 版本清单：jsDelivr 的 GitHub 镜像。
     *
     * 实测在本地网络下 raw 域名直接解析失败（UnknownHost），jsDelivr 直连 200，
     * 所以把它排在第一位；raw 留作海外或挂了代理时的兜底。
     * 代价是 jsDelivr 对分支引用有缓存（最长 12 小时），刚发布的版本可能要多等一会儿才看得到。
     */
    static final String MANIFEST_URL_CDN =
            "https://cdn.jsdelivr.net/gh/" + OWNER + "/" + REPO + "@" + BRANCH + "/update.json";

    /** 按顺序尝试，第一个通的就用 */
    static final String[] MANIFEST_SOURCES = {MANIFEST_URL_CDN, MANIFEST_URL};

    private AppConfig() {
    }
}
