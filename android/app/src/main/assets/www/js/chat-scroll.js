// <chat-scroll-controller>
export function createChatScroll({ threshold = 56, isVisible = null } = {}) {
  let box = null;
  let sid = '';
  let follow = true;
  let pendingEntry = false;
  let anchor = null;
  let frame = 0;
  let observer = null;
  let programmatic = null;
  let touchY = null;
  let lastTop = 0;

  const visible = () => {
    if (!box) return false;
    if (typeof isVisible === 'function') {
      if (!isVisible()) return false;
    } else if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
      return false;
    }
    const rect = typeof box.getBoundingClientRect === 'function' ? box.getBoundingClientRect() : null;
    return (box.clientHeight || 0) > 0 && (!rect || (rect.width > 0 && rect.height > 0));
  };
  const maxTop = () => Math.max(0, (box?.scrollHeight || 0) - (box?.clientHeight || 0));
  const atBottom = () => visible() && maxTop() - (box.scrollTop || 0) <= threshold;
  const write = (top) => {
    if (!visible()) return;
    const target = Math.max(0, Math.min(maxTop(), Number.isFinite(top) ? top : maxTop()));
    lastTop = target;
    if (Math.abs((box.scrollTop || 0) - target) < 0.5) return;
    programmatic = target;
    box.scrollTop = target;
  };
  const capture = () => {
    if (!visible()) return null;
    const rect = box.getBoundingClientRect();
    const nodes = box.querySelectorAll ? Array.from(box.querySelectorAll('[data-scroll-key]')) : [];
    for (const node of nodes) {
      const r = node.getBoundingClientRect?.();
      if (!r || r.bottom <= rect.top + 1 || r.top >= rect.bottom - 1) continue;
      return { key: node.dataset?.scrollKey || '', offset: r.top - rect.top, top: box.scrollTop || 0, height: box.scrollHeight || 0 };
    }
    return { key: '', offset: 0, top: box.scrollTop || 0, height: box.scrollHeight || 0 };
  };
  const restore = (saved, prepend = false) => {
    if (!saved || !visible()) return false;
    let target = null;
    if (saved.key) {
      const rect = box.getBoundingClientRect();
      const node = Array.from(box.querySelectorAll?.('[data-scroll-key]') || []).find((n) => n.dataset?.scrollKey === saved.key);
      const r = node?.getBoundingClientRect?.();
      if (r) target = (box.scrollTop || 0) + (r.top - rect.top) - saved.offset;
    }
    if (target == null) {
      target = saved.top;
      if (prepend) target += Math.max(0, (box.scrollHeight || 0) - saved.height);
    }
    write(target);
    // A subsequent status render must use the compensated position, not the
    // pre-prepend pixel fallback when there was no visible keyed message.
    anchor = capture();
    return true;
  };
  const scrollBottom = () => {
    if (!visible()) return false;
    follow = true;
    pendingEntry = false;
    anchor = null;
    write(maxTop());
    return true;
  };
  const remember = () => {
    if (visible() && !follow) anchor = capture();
    return anchor;
  };
  const refresh = () => {
    if (!visible()) return;
    if (pendingEntry || follow) scrollBottom();
    else if (anchor) restore(anchor);
    else anchor = capture();
  };
  const schedule = () => {
    if (frame) return;
    const run = () => { frame = 0; refresh(); };
    frame = typeof requestAnimationFrame === 'function' ? requestAnimationFrame(run) : setTimeout(run, 16);
  };
  const observe = () => {
    if (observer) observer.disconnect();
    observer = null;
    if (!box || typeof ResizeObserver !== 'function') return;
    observer = new ResizeObserver(schedule);
    observer.observe(box);
  };
  const onScroll = () => {
    if (!box) return;
    if (programmatic != null) {
      if (Math.abs((box.scrollTop || 0) - programmatic) <= 2) { programmatic = null; return; }
      programmatic = null;
    }
    if (!visible()) return;
    lastTop = box.scrollTop || 0;
    if (visible() && maxTop() - (box.scrollTop || 0) <= 2) {
      follow = true;
      pendingEntry = false;
      anchor = null;
    } else {
      follow = false;
      pendingEntry = false;
      anchor = capture();
    }
  };
  const pauseFollow = () => {
    follow = false;
    pendingEntry = false;
    programmatic = null;
    anchor = capture();
  };
  const onWheel = (event) => { if (event.deltaY < 0) pauseFollow(); };
  const onTouchStart = (event) => { touchY = event.touches?.[0]?.clientY ?? null; };
  const onTouchMove = (event) => {
    const y = event.touches?.[0]?.clientY;
    if (touchY != null && y != null && y > touchY) pauseFollow();
    touchY = y ?? null;
  };
  const onTouchEnd = () => { touchY = null; };
  const onLoad = () => schedule();
  const onToggle = () => schedule();
  const attach = (nextBox) => {
    if (box) {
      box.removeEventListener?.('scroll', onScroll);
      box.removeEventListener?.('wheel', onWheel);
      box.removeEventListener?.('touchstart', onTouchStart);
      box.removeEventListener?.('touchmove', onTouchMove);
      box.removeEventListener?.('touchend', onTouchEnd);
      box.removeEventListener?.('load', onLoad, true);
      box.removeEventListener?.('toggle', onToggle, true);
    }
    if (observer) observer.disconnect();
    observer = null;
    programmatic = null;
    box = nextBox || null;
    if (!box) return;
    box.addEventListener?.('scroll', onScroll);
    box.addEventListener?.('wheel', onWheel, { passive: true });
    box.addEventListener?.('touchstart', onTouchStart, { passive: true });
    box.addEventListener?.('touchmove', onTouchMove, { passive: true });
    box.addEventListener?.('touchend', onTouchEnd, { passive: true });
    box.addEventListener?.('load', onLoad, true);
    box.addEventListener?.('toggle', onToggle, true);
    observe();
    schedule();
  };
  const begin = (nextSid = sid) => {
    const next = nextSid == null ? '' : String(nextSid);
    if (next !== sid) {
      sid = next;
      follow = true;
      pendingEntry = !!sid;
      anchor = null;
    }
    if (follow && !pendingEntry && visible() && (box.scrollTop || 0) < lastTop - 2) pauseFollow();
    if (!follow && (!anchor || Math.abs((box?.scrollTop || 0) - anchor.top) > 2)) remember();
  };
  const end = ({ sid: nextSid = sid, ready = true, prepend = false } = {}) => {
    const next = nextSid == null ? '' : String(nextSid);
    if (next !== sid) {
      sid = next;
      follow = true;
      pendingEntry = !!sid;
      anchor = null;
    }
    observe();
    if (!ready || !visible()) return;
    if (pendingEntry || follow) scrollBottom();
    else restore(anchor, prepend);
  };
  const enter = (nextSid = sid) => {
    sid = nextSid == null ? '' : String(nextSid);
    follow = true;
    pendingEntry = !!sid;
    anchor = null;
    if (visible()) scrollBottom();
    else schedule();
  };
  const resume=(nextSid,saved)=>{sid=nextSid||'';follow=saved.follow;pendingEntry=!!saved.pendingEntry;anchor=saved.anchor||null;observe();};
  return {
    resume,
    attach,
    begin,
    end,
    enter,
    remember,
    refresh,
    capture: remember,
    get state() { return { sid, follow, pendingEntry, anchor }; },
  };
}
// </chat-scroll-controller>
