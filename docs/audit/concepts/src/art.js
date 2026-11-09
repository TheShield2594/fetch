// Abstract sample artwork (no real titles): a sun over layered hills, coloured by a palette.
const PAL = {
  field:["#3a3210","#8a7430","#f1f0a8","#1a1408"], creek:["#1c2550","#4c58a8","#c9c2ff","#0c1028"],
  kestrel:["#3b1446","#96408f","#ffc1e0","#190a22"], signal:["#10283c","#3a6c98","#bcd2ff","#081420"],
  north:["#0f3a36","#2f8f86","#c8fff0","#061a18"], ember:["#40150c","#b0522a","#ffd9a0","#1c0a06"],
  slate:["#1b1f26","#55606f","#e6edf7","#0c0e12"], moss:["#18301a","#4f7f3c","#e4f5b0","#0a160b"],
  plum:["#2a1038","#6a3a9a","#e7c8ff","#120618"], rust:["#3a1a10","#8f4a30","#ffcfb0","#180a06"]
};
function artSvg(p, sunX=68, sunY=42, r=9){
  const [a,b,s,d]=PAL[p];
  return `<svg class="art" viewBox="0 0 160 90" preserveAspectRatio="xMidYMid slice" xmlns="http://www.w3.org/2000/svg">
  <defs><radialGradient id="g${p}" cx="${sunX}%" cy="${sunY}%" r="60%"><stop offset="0" stop-color="${b}"/><stop offset="1" stop-color="${a}"/></radialGradient>
  <radialGradient id="h${p}" cx="50%" cy="50%" r="50%"><stop offset="0" stop-color="${s}" stop-opacity=".55"/><stop offset="1" stop-color="${s}" stop-opacity="0"/></radialGradient></defs>
  <rect width="160" height="90" fill="url(#g${p})"/>
  <circle cx="${sunX*1.6}" cy="${sunY*.9}" r="${r*3}" fill="url(#h${p})"/>
  <circle cx="${sunX*1.6}" cy="${sunY*.9}" r="${r}" fill="${s}"/>
  <path d="M0 62 C40 52 70 66 110 58 S150 54 160 60 V90 H0Z" fill="${d}" opacity=".55"/>
  <path d="M0 74 C30 66 80 80 120 70 S150 72 160 68 V90 H0Z" fill="${d}" opacity=".9"/></svg>`;
}
document.querySelectorAll("[data-art]").forEach(el=>{
  const [p,x,y,r]=el.dataset.art.split(","); el.insertAdjacentHTML("afterbegin", artSvg(p, +x||68, +y||42, +r||9));
});
const I = {
 home:'<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V20h14V9.5"/>', search:'<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
 lib:'<rect x="4" y="9" width="16" height="11" rx="2"/><path d="M6 6h12M8 3h8"/>', disc:'<circle cx="12" cy="12" r="9"/><path d="m15.5 8.5-2 5-5 2 2-5z"/>',
 cal:'<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/>', set:'<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1"/>',
 play:'<path d="M7 4.5v15l12-7.5z" fill="currentColor" stroke="none"/>', pause:'<path d="M7 4h4v16H7zM13 4h4v16h-4z" fill="currentColor" stroke="none"/>',
 plus:'<path d="M12 5v14M5 12h14"/>', check:'<path d="m5 12.5 4.5 4.5L19 7.5"/>', mic:'<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/>',
 cc:'<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M10 10.5a2 2 0 1 0 0 3M17 10.5a2 2 0 1 0 0 3"/>', audio:'<path d="M4 9v6h4l5 4V5L8 9z"/><path d="M16 9a4 4 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11"/>',
 src:'<path d="M4 6h16M4 12h16M4 18h10"/>', eps:'<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M7 20h10"/>', more:'<circle cx="5" cy="12" r="1.3"/><circle cx="12" cy="12" r="1.3"/><circle cx="19" cy="12" r="1.3"/>',
 trailer:'<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M10 9v6l5-3z" fill="currentColor" stroke="none"/>', eye:'<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
 next:'<path d="M5 4.5v15l11-7.5z" fill="currentColor" stroke="none"/><path d="M19 5v14"/>', back10:'<path d="M4 12a8 8 0 1 0 3-6.2"/><path d="M4 4v4h4"/>', fwd30:'<path d="M20 12a8 8 0 1 1-3-6.2"/><path d="M20 4v4h-4"/>',
 hist:'<path d="M4 12a8 8 0 1 0 3-6.2"/><path d="M4 4v4h4M12 8v4l3 2"/>'
};
document.querySelectorAll("[data-i]").forEach(el=>{ el.innerHTML=`<svg class="ico" viewBox="0 0 24 24">${I[el.dataset.i]}</svg>`; });
document.querySelectorAll("[data-rail]").forEach(el=>{
  const on=+el.dataset.rail; const names=["home","search","disc","lib","set"];
  el.innerHTML=names.map((n,i)=>`<div class="it ${i===on?'on':''}"><svg class="ico" viewBox="0 0 24 24">${I[n]}</svg></div>`).join("")+
  `<div class="abs" style="bottom:27px;width:30px;height:30px;border-radius:50%;background:#C26A3D;display:flex;align-items:center;justify-content:center;font-weight:700;font-size:13px">A</div>`;
});
