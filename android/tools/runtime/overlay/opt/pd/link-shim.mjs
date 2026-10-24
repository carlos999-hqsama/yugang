// Android 的应用数据分区（FBE/FUSE）经常拒绝硬链接；dsh 用 link() 做「不覆盖地发布文件」。
// 这里在运行期给 fs.link / fs.promises.link 加一个回退：平台拒绝硬链接时改为「独占复制」，
// 语义仍是目标已存在则失败（COPYFILE_EXCL）。不改动 dsh 源码。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
// PRoot link2symlink masks EPERM as success. Workspace files are also read by
// Android outside PRoot, so publish their hard links as exclusive real copies.
const workDocument = (dst) => {
  const name = path.resolve(dst instanceof URL ? fileURLToPath(dst) : String(dst));
  return name.startsWith('/BOT/') || name.startsWith('/CONFIG/') || name.startsWith('/PLUGINS/');
};
import { syncBuiltinESMExports } from 'node:module';
const FALLBACK = new Set(['EPERM', 'EACCES', 'ENOSYS', 'EXDEV', 'ENOTSUP', 'EOPNOTSUPP', 'EMLINK']);
const { COPYFILE_EXCL } = fs.constants;
const pLink = fs.promises.link.bind(fs.promises);
fs.promises.link = async (src, dst) => {
  if (workDocument(dst)) return fs.promises.copyFile(src, dst, COPYFILE_EXCL);
  try { return await pLink(src, dst); }
  catch (e) { if (!FALLBACK.has(e.code)) throw e; await fs.promises.copyFile(src, dst, COPYFILE_EXCL); }
};
const sLink = fs.linkSync;
fs.linkSync = (src, dst) => {
  if (workDocument(dst)) return fs.copyFileSync(src, dst, COPYFILE_EXCL);
  try { return sLink(src, dst); }
  catch (e) { if (!FALLBACK.has(e.code)) throw e; fs.copyFileSync(src, dst, COPYFILE_EXCL); }
};
const cLink = fs.link;
fs.link = (src, dst, cb) => {
  if (workDocument(dst)) return fs.copyFile(src, dst, COPYFILE_EXCL, cb);
  return cLink(src, dst, (e) => {
  if (!e || !FALLBACK.has(e.code)) return cb(e);
  fs.copyFile(src, dst, COPYFILE_EXCL, cb);
  });
};
syncBuiltinESMExports();
