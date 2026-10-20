import type { Db } from '../connection.js';
import { DeviceRepository } from './device-repository.js';
import { FriendRepository } from './friend-repository.js';
import { SqliteLocationRepository } from './location-repository.js';
import { TokenRepository } from './token-repository.js';
import { UserRepository } from './user-repository.js';

export * from './types.js';
export { UserRepository } from './user-repository.js';
export { DeviceRepository } from './device-repository.js';
export { FriendRepository, normalizePair } from './friend-repository.js';
export { TokenRepository } from './token-repository.js';
export {
  SqliteLocationRepository,
  encodeCursor,
  decodeCursor,
  isStale,
} from './location-repository.js';

export interface Repositories {
  users: UserRepository;
  devices: DeviceRepository;
  friends: FriendRepository;
  tokens: TokenRepository;
  locations: SqliteLocationRepository;
}

export function createRepositories(db: Db): Repositories {
  return {
    users: new UserRepository(db),
    devices: new DeviceRepository(db),
    friends: new FriendRepository(db),
    tokens: new TokenRepository(db),
    locations: new SqliteLocationRepository(db),
  };
}
