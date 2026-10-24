// Runtime tools import the pinned packaged DSH dependency; local tests point to the same public cache.
import {pathToFileURL} from 'node:url';
import path from 'node:path';
export async function resolve(specifier,context,nextResolve){
  if(specifier.startsWith('/opt/pd/dsh/') && process.env.PD_DSH_VENDOR_ROOT){
    return nextResolve(pathToFileURL(path.join(process.env.PD_DSH_VENDOR_ROOT,specifier.slice('/opt/pd/dsh/'.length))).href,context);
  }
  if(specifier.startsWith('./dsh/node_modules/') && process.env.PD_DSH_VENDOR_ROOT){
    return nextResolve(pathToFileURL(path.join(process.env.PD_DSH_VENDOR_ROOT,specifier.slice('./dsh/'.length))).href,context);
  }
  return nextResolve(specifier,context);
}
