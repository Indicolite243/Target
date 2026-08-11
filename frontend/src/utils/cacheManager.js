// 缓存管理工具
// 用于管理API调用的缓存，提高性能和用户体验

import { CACHE_CONFIG } from '../config/api.config.js';

class CacheManager {
  constructor() {
    this.cache = new Map();
    this.stats = {
      hits: 0,
      misses: 0,
      sets: 0,
      deletes: 0
    };
  }

  /**
   * 生成缓存键
   * @param {string} prefix - 缓存键前缀
   * @param {string} key - 具体键名
   * @returns {string} 完整的缓存键
   */
  generateKey(prefix, key) {
    return `${prefix}${key}`;
  }

  /**
   * 设置缓存
   * @param {string} prefix - 缓存键前缀
   * @param {string} key - 具体键名
   * @param {any} data - 要缓存的数据
   * @param {number} ttl - 缓存生存时间（毫秒）
   */
  set(prefix, key, data, ttl = null) {
    const cacheKey = this.generateKey(prefix, key);
    const ttlValue = ttl || CACHE_CONFIG.TTL[prefix.toUpperCase()] || 5 * 60 * 1000; // 默认5分钟

    const cacheItem = {
      data: data,
      timestamp: Date.now(),
      ttl: ttlValue,
      expiresAt: Date.now() + ttlValue
    };

    this.cache.set(cacheKey, cacheItem);
    this.stats.sets++;

    console.log(`💾 缓存已设置: ${cacheKey}, TTL: ${ttlValue}ms`);
  }

  /**
   * 获取缓存
   * @param {string} prefix - 缓存键前缀
   * @param {string} key - 具体键名
   * @returns {any|null} 缓存的数据或null
   */
  get(prefix, key) {
    const cacheKey = this.generateKey(prefix, key);
    const cacheItem = this.cache.get(cacheKey);

    if (!cacheItem) {
      this.stats.misses++;
      return null;
    }

    // 检查是否过期
    if (Date.now() > cacheItem.expiresAt) {
      this.delete(prefix, key);
      this.stats.misses++;
      return null;
    }

    this.stats.hits++;
    console.log(`🎯 缓存命中: ${cacheKey}`);
    return cacheItem.data;
  }

  /**
   * 删除缓存
   * @param {string} prefix - 缓存键前缀
   * @param {string} key - 具体键名
   */
  delete(prefix, key) {
    const cacheKey = this.generateKey(prefix, key);
    const deleted = this.cache.delete(cacheKey);

    if (deleted) {
      this.stats.deletes++;
      console.log(`🗑️ 缓存已删除: ${cacheKey}`);
    }
  }

  /**
   * 清空指定前缀的所有缓存
   * @param {string} prefix - 缓存键前缀
   */
  clearPrefix(prefix) {
    const keysToDelete = [];

    for (const key of this.cache.keys()) {
      if (key.startsWith(prefix)) {
        keysToDelete.push(key);
      }
    }

    keysToDelete.forEach(key => {
      this.cache.delete(key);
      this.stats.deletes++;
    });

    console.log(`🧹 已清空前缀为 ${prefix} 的缓存，共 ${keysToDelete.length} 项`);
  }

  /**
   * 清空所有缓存
   */
  clearAll() {
    const size = this.cache.size;
    this.cache.clear();
    this.stats.deletes += size;
    console.log(`🧹 已清空所有缓存，共 ${size} 项`);
  }

  /**
   * 清理过期缓存
   */
  cleanup() {
    const now = Date.now();
    const keysToDelete = [];

    for (const [key, item] of this.cache.entries()) {
      if (now > item.expiresAt) {
        keysToDelete.push(key);
      }
    }

    keysToDelete.forEach(key => {
      this.cache.delete(key);
      this.stats.deletes++;
    });

    if (keysToDelete.length > 0) {
      console.log(`🧹 已清理过期缓存，共 ${keysToDelete.length} 项`);
    }
  }

  /**
   * 获取缓存统计信息
   * @returns {Object} 缓存统计信息
   */
  getStats() {
    const total = this.stats.hits + this.stats.misses;
    const hitRate = total > 0 ? (this.stats.hits / total * 100).toFixed(2) : 0;

    return {
      ...this.stats,
      total,
      hitRate: `${hitRate}%`,
      cacheSize: this.cache.size,
      memoryUsage: this.getMemoryUsage()
    };
  }

  /**
   * 获取内存使用情况
   * @returns {string} 内存使用信息
   */
  getMemoryUsage() {
    if (performance.memory) {
      const used = Math.round(performance.memory.usedJSHeapSize / 1024 / 1024);
      const total = Math.round(performance.memory.totalJSHeapSize / 1024 / 1024);
      return `${used}MB / ${total}MB`;
    }
    return 'N/A';
  }

  /**
   * 检查缓存是否存在且未过期
   * @param {string} prefix - 缓存键前缀
   * @param {string} key - 具体键名
   * @returns {boolean} 是否存在有效缓存
   */
  has(prefix, key) {
    const cacheKey = this.generateKey(prefix, key);
    const cacheItem = this.cache.get(cacheKey);

    if (!cacheItem) {
      return false;
    }

    return Date.now() <= cacheItem.expiresAt;
  }

  /**
   * 获取缓存项的剩余生存时间
   * @param {string} prefix - 缓存键前缀
   * @param {string} key - 具体键名
   * @returns {number} 剩余生存时间（毫秒）
   */
  getTTL(prefix, key) {
    const cacheKey = this.generateKey(prefix, key);
    const cacheItem = this.cache.get(cacheKey);

    if (!cacheItem) {
      return 0;
    }

    const remaining = cacheItem.expiresAt - Date.now();
    return Math.max(0, remaining);
  }

  /**
   * 更新缓存项的生存时间
   * @param {string} prefix - 缓存键前缀
   * @param {string} key - 具体键名
   * @param {number} ttl - 新的生存时间（毫秒）
   */
  updateTTL(prefix, key, ttl) {
    const cacheKey = this.generateKey(prefix, key);
    const cacheItem = this.cache.get(cacheKey);

    if (cacheItem) {
      cacheItem.ttl = ttl;
      cacheItem.expiresAt = Date.now() + ttl;
      console.log(`⏰ 已更新缓存TTL: ${cacheKey}, 新TTL: ${ttl}ms`);
    }
  }
}

// 创建全局缓存管理器实例
const cacheManager = new CacheManager();

// 定期清理过期缓存（每5分钟执行一次）
setInterval(() => {
  cacheManager.cleanup();
}, 5 * 60 * 1000);

// 导出缓存管理器实例和类
export { cacheManager, CacheManager };
export default cacheManager;
