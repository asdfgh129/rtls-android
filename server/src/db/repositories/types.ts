export type DevicePlatform = 'android' | 'ios' | 'unknown';
export type DeviceStatus = 'active' | 'revoked';
export type FriendRequestStatus = 'pending' | 'accepted' | 'rejected' | 'cancelled';
export type LocationProvider = 'gps' | 'network' | 'fused' | 'passive' | 'unknown';
export type CoordinateCrs = 'WGS84' | 'GCJ02' | 'BD09';

export interface NewLocationPoint {
  deviceId: string;
  clientMsgId: string;
  deviceTs: number;
  serverTs: number;
  latitude: number;
  longitude: number;
  accuracy?: number | null;
  provider?: LocationProvider;
  sourceCrs?: CoordinateCrs;
  speed?: number | null;
  bearing?: number | null;
  battery?: number | null;
  isMock?: boolean;
}

export interface InsertResult {
  /** 实际新插入的行数。 */
  inserted: number;
  /** 因 (device_id, client_msg_id) 冲突被跳过的行数。 */
  duplicates: number;
  /** 新插入行的 locations.id，顺序与入参一致（跳过重复项）。 */
  ids: number[];
}

export interface LatestLocation {
  deviceId: string;
  clientMsgId: string;
  latitude: number;
  longitude: number;
  accuracy: number | null;
  provider: LocationProvider;
  sourceCrs: CoordinateCrs;
  speed: number | null;
  bearing: number | null;
  battery: number | null;
  isMock: boolean;
  deviceTs: number;
  serverTs: number;
  updatedAt: number;
}

export interface HistoryCursor {
  serverTs: number;
  id: number;
}

export interface HistoryQuery {
  deviceId: string;
  /** 起始时间（含），epoch ms。 */
  from: number;
  /** 结束时间（不含），epoch ms。 */
  to: number;
  /** 最大返回点数，协议上限 5000。 */
  limit: number;
  cursor?: HistoryCursor | null;
}

export interface HistoryPoint {
  id: number;
  deviceTs: number;
  serverTs: number;
  latitude: number;
  longitude: number;
  accuracy: number | null;
  provider: LocationProvider;
  sourceCrs: CoordinateCrs;
}

export interface HistoryPage {
  points: HistoryPoint[];
  nextCursor: HistoryCursor | null;
  truncated: boolean;
}

/**
 * Repository 契约见 ADR-009。方法声明为 async，便于未来替换为
 * PostgreSQL / TDengine 等异步实现，调用方无需改动。
 */
export interface LocationRepository {
  insertBatch(points: NewLocationPoint[]): Promise<InsertResult>;
  latest(deviceId: string): Promise<LatestLocation | null>;
  queryHistory(input: HistoryQuery): Promise<HistoryPage>;
  deleteOlderThan(cutoff: Date): Promise<number>;
}
