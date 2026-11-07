const paths={
 menu:"M4 6H20 M4 12H20 M4 18H20",close:"M6 6L18 18 M18 6L6 18",
 plus:"M12 5V19 M5 12H19",left:"M9 6L15 12L9 18",right:"M15 6L9 12L15 18",
 down:"M6 9L12 15L18 9",
 pencil:"M16 3L21 8L8 21H3V16Z M13 6L18 11",
 category:"M3 3H10V10H3Z M14 3H21V10H14Z M3 14H10V21H3Z M14 14H21V21H14Z",
 source:"M5 5H19Q22 5 22 8V16Q22 19 19 19H5Q2 19 2 16V8Q2 5 5 5Z M2 10H22 M6 15H10",
 language:"M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12 M16 12A4 9 0 1 1 8 12A4 9 0 1 1 16 12 M3 12H21",
 theme:"M20 15A9 9 0 0 1 9 4A9 9 0 1 0 20 15Z",
 account:"M16 7A4 4 0 1 1 8 7A4 4 0 1 1 16 7 M4 21V19A8 8 0 0 1 20 19V21",
 sync:"M7 17H6A4 4 0 0 1 5 9A7 7 0 0 1 18 8A4.5 4.5 0 0 1 19 17H17 M12 12V21 M9 18L12 21L15 18"
};
export function icon(name,extra=""){return `<svg class="icon ${extra}" viewBox="0 0 24 24" aria-hidden="true"><path d="${paths[name]}"/></svg>`}
