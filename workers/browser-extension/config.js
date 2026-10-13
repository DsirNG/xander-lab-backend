// Change this URL to the local Gateway when developing against Wrangler.
export const API_BASE_URL = "https://api.xiaodongding.dpdns.org";

export const PLATFORM_URLS = {
  csdn: "https://mp.csdn.net/",
  juejin: "https://juejin.cn/editor/drafts/new?v=2",
  wechat_mp: "https://mp.weixin.qq.com/",
  weibo: "https://weibo.com/",
  zhihu: "https://www.zhihu.com/",
  baijiahao: "https://baijiahao.baidu.com/",
  cnblogs: "https://i.cnblogs.com/",
  douyin: "https://creator.douyin.com/",
  xiaohongshu: "https://creator.xiaohongshu.com/",
  toutiao: "https://mp.toutiao.com/",
  "51cto": "https://blog.51cto.com/",
  jianshu: "https://www.jianshu.com/",
  segmentfault: "https://segmentfault.com/",
  oschina: "https://my.oschina.net/",
  bilibili: "https://member.bilibili.com/",
  tencent_dev: "https://cloud.tencent.com/developer",
  aliyun_dev: "https://developer.aliyun.com/",
};

export const PLATFORM_COOKIE_DOMAINS = {
  csdn: ["csdn.net"],
  juejin: ["juejin.cn"],
  wechat_mp: ["mp.weixin.qq.com"],
  weibo: ["weibo.com"],
  zhihu: ["zhihu.com"],
  baijiahao: ["baidu.com"],
  cnblogs: ["cnblogs.com"],
  douyin: ["douyin.com"],
  xiaohongshu: ["xiaohongshu.com"],
  toutiao: ["toutiao.com"],
  "51cto": ["51cto.com"],
  jianshu: ["jianshu.com"],
  segmentfault: ["segmentfault.com"],
  oschina: ["oschina.net"],
  bilibili: ["bilibili.com"],
  tencent_dev: ["cloud.tencent.com", "qq.com"],
  aliyun_dev: ["aliyun.com"],
};

export const SESSION_COOKIE_NAMES = {
  csdn: ["UserToken"],
  juejin: ["sessionid_ss", "sessionid", "sid_tt"],
  wechat_mp: ["slave_user", "slave_sid"],
  weibo: ["SUB"],
  zhihu: ["z_c0"],
  baijiahao: ["BDUSS", "BDUSS_BFESS"],
  cnblogs: [".CNBlogsCookie"],
  douyin: ["sessionid", "sessionid_ss", "sid_guard"],
  xiaohongshu: ["web_session"],
  toutiao: ["sessionid", "sid_tt"],
  "51cto": ["user_id", "sso_uid"],
  jianshu: ["remember_user_token"],
  segmentfault: ["sf_remember", "sf_session"],
  oschina: ["oscid", "oscid_token"],
  bilibili: ["SESSDATA", "DedeUserID"],
  tencent_dev: ["p_skey", "skey"],
  aliyun_dev: ["login_aliyunid_ticket"],
};

export const CONNECTOR_ORIGINS = new Set([
  "https://dinqor.cn",
  "https://xiaod.dpdns.org",
  "http://localhost:5173",
  "http://localhost:3000",
]);
