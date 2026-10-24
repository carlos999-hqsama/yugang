/** Fish artwork generated with imagegen, rendered from one local 3x3 atlas.
 * SVG only supplies the crop and monochrome ink filter; no remote resources. */
export const BOT_FISH_TYPES = Object.freeze([
  'whale',
  'swordfish',
  'goldfish',
  'angelfish',
  'koi',
  'betta',
  'puffer',
  'ray',
]);

export const BOT_FISH_LABELS = Object.freeze({
  whale: '鲸鱼',
  swordfish: '剑鱼',
  goldfish: '金鱼',
  angelfish: '神仙鱼',
  koi: '锦鲤',
  betta: '斗鱼',
  puffer: '河豚',
  ray: '鳐鱼',
});

export function normalizeBotFish(value) {
  return BOT_FISH_TYPES.includes(value) ? value : 'whale';
}

// Crop around each actual fish outline, rather than the atlas cell's uneven whitespace.
const FISH_CROPS=Object.freeze({"whale":[23.463,43.963,417.073,417.073],"swordfish":[396.634,4.134,470.732,470.732],"goldfish":[855.098,56.098,387.805,387.805],"angelfish":[23.756,433.756,380.488,380.488],"koi":[439.439,440.939,395.122,395.122],"betta":[846.768,433.268,391.463,391.463],"puffer":[33.622,835.622,359.756,359.756],"ray":[408.707,790.707,436.585,436.585]});
let glyphSequence=0;
export function fishSvg(value) {
  const fish=normalizeBotFish(value),index=BOT_FISH_TYPES.indexOf(fish),x=(index%3)*418,y=Math.floor(index/3)*418;
  const token=++glyphSequence,filter='fish-ink-'+token,clip='fish-cell-'+token;
  const crop=FISH_CROPS[fish].join(' ');
  return `<svg class="bot-fish" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" aria-hidden="true" role="presentation" data-fish="${fish}">
    <defs><clipPath id="${clip}" clipPathUnits="userSpaceOnUse"><rect x="${x}" y="${y}" width="418" height="418"/></clipPath><filter id="${filter}" color-interpolation-filters="sRGB"><feColorMatrix type="matrix" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  -.3333 -.3333 -.3334 0 1"/><feComponentTransfer><feFuncA type="table" tableValues="0 0 1 1"/></feComponentTransfer></filter></defs>
    <svg x="0" y="0" width="48" height="48" viewBox="${crop}" overflow="hidden"><image href="img/fish-atlas.png" width="1254" height="1254" filter="url(#${filter})" clip-path="url(#${clip})"/></svg>
  </svg>`;
}
