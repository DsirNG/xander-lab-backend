/** Platforms that can store a browser session in DinQorAI. Publishing support is a separate capability. */
export const CONNECTABLE_PLATFORMS = [
  "csdn", "juejin", "wechat_mp", "weibo", "zhihu", "baijiahao", "cnblogs",
  "douyin", "xiaohongshu", "toutiao", "51cto", "jianshu", "segmentfault",
  "oschina", "bilibili", "tencent_dev", "aliyun_dev",
] as const;

export type ConnectablePlatform = typeof CONNECTABLE_PLATFORMS[number];

export const PLATFORM_SESSION_COOKIE_NAMES: Record<ConnectablePlatform, readonly string[]> = {
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

export function isConnectablePlatform(value: string): value is ConnectablePlatform {
  return (CONNECTABLE_PLATFORMS as readonly string[]).includes(value);
}
