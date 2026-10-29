import type { D1Binding } from "@dinqor/worker-runtime";

export class AgentWeatherError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "AgentWeatherError";
  }
}

export interface AgentWeatherReport {
  place: string;
  condition: string;
  temperatureC: number | null;
  highC: number | null;
  lowC: number | null;
  apparentTemperatureC: number | null;
  windSpeedMs: number | null;
  rainProbabilityPercent: number | null;
  precipitationMm: number | null;
  forecastFor: "current" | "today" | "tomorrow";
  retrievedAt: string;
  answer: string;
  attribution: string[];
}

interface WeatherEnvironment {
  DB: D1Binding;
  WEATHER_USER_AGENT?: string;
  WEATHER_GEOCODING_URL?: string;
  WEATHER_FORECAST_URL?: string;
}

interface GeocodingResult {
  lat?: string;
  lon?: string;
  display_name?: string;
  address?: Record<string, unknown>;
}

interface ForecastTimeseries {
  time?: string;
  data?: {
    instant?: { details?: Record<string, unknown> };
    next_6_hours?: {
      summary?: { symbol_code?: string };
      details?: Record<string, unknown>;
    };
  };
}

interface ForecastPayload {
  properties?: { timeseries?: ForecastTimeseries[] };
}

const ATTRIBUTION = "天气数据：MET Norway（CC BY 4.0，https://creativecommons.org/licenses/by/4.0/）。";
const OSM_ATTRIBUTION = "地点数据：© OpenStreetMap contributors（ODbL，https://www.openstreetmap.org/copyright）。";
const ATTRIBUTION_EN = "Weather data: MET Norway (CC BY 4.0, https://creativecommons.org/licenses/by/4.0/).";
const OSM_ATTRIBUTION_EN = "Place data: © OpenStreetMap contributors (ODbL, https://www.openstreetmap.org/copyright).";
const MAX_WEATHER_RESPONSE_BYTES = 2 * 1024 * 1024;

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function finite(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function conditionLabel(symbol: unknown, language: "zh" | "en"): string {
  const code = typeof symbol === "string" ? symbol.toLowerCase().replace(/_(day|night|polartwilight)$/, "") : "";
  const labels: Array<[string, string, string]> = [
    ["thunder", "雷雨", "thunderstorms"], ["heavyrain", "大雨", "heavy rain"],
    ["sleet", "雨夹雪", "sleet"], ["fog", "有雾", "fog"], ["showers", "阵雨", "showers"],
    ["rain", "有雨", "rain"], ["snow", "有雪", "snow"], ["partlycloudy", "局部多云", "partly cloudy"],
    ["cloudy", "多云", "cloudy"], ["fair", "晴间多云", "fair"], ["clearsky", "晴", "clear"],
  ];
  const found = labels.find(([key]) => code.includes(key));
  return found ? found[language === "zh" ? 1 : 2] : language === "zh" ? "天气状况暂未提供" : "weather condition unavailable";
}

function localPlace(address: Record<string, unknown>, fallback: string): string {
  for (const key of ["city", "town", "village", "municipality", "county", "state"]) {
    const value = address[key];
    if (typeof value === "string" && value.trim()) return value.trim().slice(0, 80);
  }
  return fallback;
}

function requestCache(): Cache | undefined {
  const storage = (globalThis as typeof globalThis & { caches?: { default?: Cache } }).caches;
  return storage?.default;
}

async function fetchJson(
  url: URL,
  userAgent: string,
  cacheSeconds: number,
  beforeFetch?: () => Promise<void>,
): Promise<unknown> {
  const cache = cacheSeconds > 0 ? requestCache() : undefined;
  const key = new Request(url.toString(), { method: "GET" });
  const readCache = async (): Promise<unknown | undefined> => {
    if (!cache) return undefined;
    const cached = await cache.match(key).catch(() => undefined);
    if (cached) {
      try { return await cached.json() as unknown; }
      catch { await cache.delete(key).catch(() => false); }
    }
    return undefined;
  };
  const cached = await readCache();
  if (cached !== undefined) return cached;
  await beforeFetch?.();
  // A concurrent request may have populated the cache while this request waited
  // for the public geocoder's shared rate-limit slot.
  const afterWait = await readCache();
  if (afterWait !== undefined) return afterWait;

  let response: Response;
  try {
    response = await fetch(url, {
      headers: { accept: "application/json", "user-agent": userAgent },
      redirect: "manual",
      signal: AbortSignal.timeout(12_000),
    });
  } catch {
    throw new AgentWeatherError(503, "天气数据服务暂时无法连接，请稍后重试");
  }
  if (!response.ok) {
    throw new AgentWeatherError(response.status === 429 ? 503 : 502, "天气数据服务暂时不可用");
  }
  let payload: unknown;
  try {
    const body = await response.text();
    if (new TextEncoder().encode(body).byteLength > MAX_WEATHER_RESPONSE_BYTES) {
      throw new AgentWeatherError(502, "天气数据服务返回内容过大");
    }
    payload = JSON.parse(body) as unknown;
  }
  catch (error) {
    if (error instanceof AgentWeatherError) throw error;
    throw new AgentWeatherError(502, "天气数据服务返回了无效数据");
  }

  if (cache) {
    const expires = response.headers.get("expires");
    const maxAge = expires ? Math.floor((Date.parse(expires) - Date.now()) / 1000) : cacheSeconds;
    const ttl = Math.max(0, Math.min(cacheSeconds, maxAge));
    if (ttl > 0) {
      await cache.put(key, new Response(JSON.stringify(payload), {
        headers: { "content-type": "application/json", "cache-control": `public, max-age=${ttl}` },
      })).catch(() => undefined);
    }
  }
  return payload;
}

function timeLabel(timeZone: string | undefined, language: "zh" | "en"): string {
  try {
    return new Intl.DateTimeFormat(language === "zh" ? "zh-CN" : "en-US", {
      dateStyle: "medium", timeStyle: "short", timeZone: timeZone || "UTC",
    }).format(new Date());
  } catch {
    return new Date().toISOString();
  }
}

function dateKey(date: Date, timeZone: string): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    year: "numeric", month: "2-digit", day: "2-digit", timeZone,
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map(({ type, value }) => [type, value]));
  return `${values.year}-${values.month}-${values.day}`;
}

function shiftDate(key: string, days: number): string {
  const [year, month, day] = key.split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, day + days)).toISOString().slice(0, 10);
}

export class AgentWeatherService {
  constructor(private readonly env: WeatherEnvironment) {}

  ensureConfigured(): void {
    this.userAgent();
  }

  async byCity(cityValue: unknown, forecastForValue: unknown, timeZone?: string, language: "zh" | "en" = "zh"): Promise<AgentWeatherReport> {
    if (typeof cityValue !== "string" || !cityValue.trim() || cityValue.trim().length > 100) {
      throw new AgentWeatherError(400, "请提供一个城市名称");
    }
    const city = cityValue.trim();
    const userAgent = this.userAgent();
    const geocodingUrl = this.endpoint(this.env.WEATHER_GEOCODING_URL,
      "https://nominatim.openstreetmap.org/search");
    geocodingUrl.searchParams.set("q", city);
    geocodingUrl.searchParams.set("format", "jsonv2");
    geocodingUrl.searchParams.set("addressdetails", "1");
    geocodingUrl.searchParams.set("limit", "1");
    const results = await fetchJson(geocodingUrl, userAgent, 24 * 60 * 60, () => this.acquireGeocodingSlot());
    if (!Array.isArray(results) || results.length === 0) throw new AgentWeatherError(404, `没有找到“${city}”的位置`);
    const result = results[0] as GeocodingResult;
    const latitude = Number(result.lat);
    const longitude = Number(result.lon);
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)
      || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
      throw new AgentWeatherError(502, "地点查询没有返回有效坐标");
    }
    const place = localPlace(record(result.address), city);
    return this.forecast(latitude, longitude, place, forecastForValue, timeZone, language, true, true);
  }

  async byCoordinates(
    latitudeValue: unknown, longitudeValue: unknown, forecastForValue: unknown, timeZone?: string, language: "zh" | "en" = "zh",
  ): Promise<AgentWeatherReport> {
    const latitude = finite(latitudeValue);
    const longitude = finite(longitudeValue);
    if (latitude === null || longitude === null || latitude < -90 || latitude > 90
      || longitude < -180 || longitude > 180) {
      throw new AgentWeatherError(400, "天气查询坐标无效");
    }
    return this.forecast(latitude, longitude, "当前位置", forecastForValue, timeZone, language, false, false);
  }

  private async forecast(
    latitude: number,
    longitude: number,
    place: string,
    forecastForValue: unknown,
    timeZone: string | undefined,
    language: "zh" | "en",
    withPlaceAttribution: boolean,
    useCache: boolean,
  ): Promise<AgentWeatherReport> {
    const forecastFor = forecastForValue === "tomorrow" ? "tomorrow"
      : forecastForValue === "today" ? "today" : "current";
    const userAgent = this.userAgent();
    const url = this.endpoint(this.env.WEATHER_FORECAST_URL,
      "https://api.met.no/weatherapi/locationforecast/2.0/complete");
    // MET Norway requires no more than four decimal places for cacheable coordinates.
    url.searchParams.set("lat", latitude.toFixed(4));
    url.searchParams.set("lon", longitude.toFixed(4));
    const payload = await fetchJson(url, userAgent, useCache ? 15 * 60 : 0) as ForecastPayload;
    const rows = payload?.properties?.timeseries;
    if (!Array.isArray(rows) || rows.length === 0) throw new AgentWeatherError(502, "天气数据暂缺");
    const now = Date.now();
    const zone = timeZone || "UTC";
    const targetDate = forecastFor === "current" ? dateKey(new Date(now), zone)
      : shiftDate(dateKey(new Date(now), zone), forecastFor === "tomorrow" ? 1 : 0);
    const dayRows = forecastFor === "current" ? [] : rows.filter((row) => {
      const timestamp = Date.parse(row.time ?? "");
      return Number.isFinite(timestamp) && dateKey(new Date(timestamp), zone) === targetDate;
    });
    if (forecastFor !== "current" && dayRows.length === 0) {
      throw new AgentWeatherError(502, "该日期暂时没有可用的天气预报");
    }
    const selected = forecastFor === "current"
      ? rows.find((row) => Date.parse(row.time ?? "") >= now) ?? rows[0]
      : dayRows.find((row) => {
        const hour = Number(new Intl.DateTimeFormat("en", { hour: "2-digit", hourCycle: "h23", timeZone: zone })
          .format(new Date(Date.parse(row.time ?? ""))));
        return hour >= 11 && hour <= 13;
      }) ?? dayRows[Math.floor(dayRows.length / 2)];
    const data = record(selected.data);
    const instant = record(record(data.instant).details);
    const sixHours = record(data.next_6_hours);
    const sixDetails = record(sixHours.details);
    const dayDetails = dayRows.map((row) => {
      const rowData = record(row.data);
      return {
        instant: record(record(rowData.instant).details),
        next: record(record(rowData.next_6_hours).details),
      };
    });
    const dailyTemperatures = dayDetails.map((row) => finite(row.instant.air_temperature))
      .filter((value): value is number => value !== null);
    const dailyProbabilities = dayDetails.map((row) => finite(row.next.probability_of_precipitation))
      .filter((value): value is number => value !== null);
    const dailyPrecipitation = dayDetails.map((row) => finite(row.next.precipitation_amount))
      .filter((value): value is number => value !== null);
    const symbol = record(sixHours.summary).symbol_code;
    const condition = conditionLabel(symbol, language);
    const temperatureC = finite(instant.air_temperature);
    const apparentTemperatureC = finite(instant.feels_like_temperature);
    const windSpeedMs = finite(instant.wind_speed);
    const rainProbabilityPercent = forecastFor === "current"
      ? finite(sixDetails.probability_of_precipitation) : dailyProbabilities.length ? Math.max(...dailyProbabilities) : null;
    const precipitationMm = forecastFor === "current"
      ? finite(sixDetails.precipitation_amount) : dailyPrecipitation.length ? Math.max(...dailyPrecipitation) : null;
    const highC = dailyTemperatures.length ? Math.max(...dailyTemperatures) : null;
    const lowC = dailyTemperatures.length ? Math.min(...dailyTemperatures) : null;
    const rainySymbol = typeof symbol === "string" && /rain|snow|sleet|thunder|fog/i.test(symbol);
    const risky = (rainProbabilityPercent !== null && rainProbabilityPercent >= 50)
      || (precipitationMm !== null && precipitationMm >= 1)
      || (windSpeedMs !== null && windSpeedMs >= 10)
      || rainySymbol;
    const conditionKnown = condition !== "天气状况暂未提供" && condition !== "weather condition unavailable";
    const insufficientData = windSpeedMs === null
      || (rainProbabilityPercent === null && precipitationMm === null && !conditionKnown);
    const advice = language === "zh"
      ? risky ? "外出建议：建议带好雨具并留意天气变化；风较大时减少户外停留。"
        : insufficientData ? "外出建议：部分天气指标缺失，暂时无法可靠判断是否适合外出；出发前请查看当地预报。"
          : "外出建议：目前没有明显降雨或强风信号，适合安排短时户外活动；出发前可再查看当地预报。"
      : risky ? "Outdoor advice: Bring rain protection and watch for changing conditions; limit time outside if winds are strong."
        : insufficientData ? "Outdoor advice: Some forecast indicators are unavailable, so I cannot reliably assess outdoor conditions. Check the local forecast before leaving."
          : "Outdoor advice: No clear signal of heavy rain or strong wind; a short outdoor activity looks reasonable. Check the local forecast before leaving.";
    const periodLabel = forecastFor === "tomorrow" ? "明天预报" : forecastFor === "today" ? "今天预报" : "当前时段预报";
    const periodLabelEn = forecastFor === "tomorrow" ? "tomorrow" : forecastFor === "today" ? "today" : "now";
    const detailsZh = [
      `${place}${periodLabel}：${condition}`,
      temperatureC === null ? "气温数据暂缺" : `气温 ${temperatureC}℃`,
      apparentTemperatureC === null ? "" : `体感 ${apparentTemperatureC}℃`,
      windSpeedMs === null ? "风速数据暂缺" : `风速 ${windSpeedMs} m/s`,
      rainProbabilityPercent === null ? "降雨概率暂未提供" : forecastFor === "current"
        ? `未来 6 小时降雨概率 ${rainProbabilityPercent}%` : `该日各预报时段最高降雨概率 ${rainProbabilityPercent}%`,
      precipitationMm === null ? "" : forecastFor === "current"
        ? `未来 6 小时预计降水 ${precipitationMm} mm` : `单个预报时段最高降水量 ${precipitationMm} mm`,
      forecastFor === "current" || highC === null || lowC === null ? "" : `预报气温范围 ${lowC}–${highC}℃`,
    ].filter(Boolean).join("；");
    const detailsEn = [
      `Weather for ${place === "当前位置" ? "your current location" : place} ${periodLabelEn}: ${condition}`,
      temperatureC === null ? "temperature unavailable" : `${temperatureC}°C`,
      apparentTemperatureC === null ? "" : `feels like ${apparentTemperatureC}°C`,
      windSpeedMs === null ? "wind speed unavailable" : `wind ${windSpeedMs} m/s`,
      rainProbabilityPercent === null ? "precipitation probability unavailable" : forecastFor === "current"
        ? `precipitation probability over the next 6 hours: ${rainProbabilityPercent}%`
        : `highest precipitation probability for the day: ${rainProbabilityPercent}%`,
      precipitationMm === null ? "" : forecastFor === "current"
        ? `expected precipitation over the next 6 hours: ${precipitationMm} mm`
        : `highest forecast period precipitation: ${precipitationMm} mm`,
      forecastFor === "current" || highC === null || lowC === null ? "" : `forecast temperature range ${lowC}–${highC}°C`,
    ].filter(Boolean).join("; ");
    const attribution = withPlaceAttribution
      ? language === "zh" ? [ATTRIBUTION, OSM_ATTRIBUTION] : [ATTRIBUTION_EN, OSM_ATTRIBUTION_EN]
      : [language === "zh" ? ATTRIBUTION : ATTRIBUTION_EN];
    return {
      place, condition, temperatureC, highC, lowC, apparentTemperatureC, windSpeedMs,
      rainProbabilityPercent, precipitationMm, forecastFor, retrievedAt: new Date(now).toISOString(),
      answer: language === "zh"
        ? `${detailsZh}。${advice}\n\n${attribution.join(" ")} 数据时间：${timeLabel(timeZone, language)}。`
        : `${detailsEn}. ${advice}\n\n${attribution.join(" ")} Retrieved at ${timeLabel(timeZone, language)}.`,
      attribution,
    };
  }

  private userAgent(): string {
    const value = this.env.WEATHER_USER_AGENT?.trim();
    if (!value || value.length > 200 || /\r|\n/.test(value)) {
      throw new AgentWeatherError(503, "天气服务标识尚未配置，请设置可联系的产品网址或邮箱");
    }
    return value;
  }

  private async acquireGeocodingSlot(): Promise<void> {
    for (let attempt = 0; attempt < 20; attempt += 1) {
      const now = Date.now();
      const acquired = await this.env.DB.prepare(`
        UPDATE agent_weather_geocoding_rate_limit
        SET next_request_at = ?
        WHERE id = 1 AND next_request_at <= ?
        RETURNING id
      `).bind(now + 1_050, now).first<{ id: number }>();
      if (acquired) return;
      const row = await this.env.DB.prepare(`
        SELECT next_request_at AS nextRequestAt
        FROM agent_weather_geocoding_rate_limit WHERE id = 1
      `).first<{ nextRequestAt: number }>();
      if (!row) throw new AgentWeatherError(503, "天气地点服务尚未初始化");
      await new Promise((resolve) => setTimeout(resolve, Math.max(25, Math.min(1_100, row.nextRequestAt - Date.now()))));
    }
    throw new AgentWeatherError(503, "天气地点服务当前请求较多，请稍后重试");
  }

  private endpoint(value: string | undefined, fallback: string): URL {
    try {
      const url = new URL(value?.trim() || fallback);
      if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) {
        throw new Error("Invalid weather endpoint");
      }
      return url;
    } catch {
      throw new AgentWeatherError(503, "天气服务地址配置无效");
    }
  }
}
