import { v7 as uuidv7 } from 'uuid';

/** 应用层生成 UUIDv7（时间有序，利于 B-tree 局部性）。 */
export function newId(): string {
  return uuidv7();
}

/** 统一的时间戳：Unix epoch 毫秒（UTC）。 */
export function nowMs(): number {
  return Date.now();
}
