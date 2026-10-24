const namespace = 'pf';

export function Text(value, {variant = 'body', className = ''} = {}) {
  const node = document.createElement('div');
  node.className = `${namespace}-text ${variant} ${className}`.trim();
  node.textContent = String(value ?? '');
  return node;
}

export function Button({text, children, variant = 'secondary', disabled = false, onClick, type = 'button', className = ''} = {}) {
  const node = document.createElement('button');
  node.type = type;
  node.disabled = disabled;
  node.className = `${namespace}-button ${variant} ${className}`.trim();
  if (children != null) node.append(children);
  else node.textContent = String(text ?? '');
  if (onClick) node.addEventListener('click', event => onClick(event, node));
  return node;
}

export function Card({title, subtitle, children = [], className = ''} = {}) {
  const node = document.createElement('section');
  node.className = `${namespace}-card ${className}`.trim();
  if (title || subtitle) {
    const head = document.createElement('header');
    if (title) {
      const heading = document.createElement('h2');
      heading.textContent = title;
      head.append(heading);
    }
    if (subtitle) head.append(Text(subtitle, {variant: 'muted'}));
    node.append(head);
  }
  const body = document.createElement('div');
  body.className = `${namespace}-card-body`;
  body.append(...Array.isArray(children) ? children : [children]);
  node.append(body);
  return node;
}

export function List({items = [], onSelect, className = ''} = {}) {
  const node = document.createElement('ul');
  node.className = `${namespace}-list ${className}`.trim();
  for (const item of items) {
    const li = document.createElement('li');
    li.tabIndex = 0;
    li.setAttribute('role', 'button');
    const value = typeof item === 'object' && item !== null ? item : {title: item};
    li.append(Text(value.title ?? '', {variant: 'title'}));
    if (value.subtitle != null) li.append(Text(value.subtitle, {variant: 'muted'}));
    const select = () => onSelect?.(value, li);
    li.addEventListener('click', select);
    li.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        select();
      }
    });
    node.append(li);
  }
  return node;
}

export function Input({value = '', placeholder = '', type = 'text', disabled = false, onInput, className = ''} = {}) {
  const node = document.createElement('input');
  node.type = type;
  node.value = value ?? '';
  node.placeholder = placeholder;
  node.disabled = disabled;
  node.className = `${namespace}-input ${className}`.trim();
  if (onInput) node.addEventListener('input', event => onInput(event.target.value, event, node));
  return node;
}
