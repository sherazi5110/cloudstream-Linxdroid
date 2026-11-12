// Renders the Stream Goat logo (packaging/linux/cloudstream-linux.svg) to every icon the apps and packages use:
// Linux/Windows app icons, the web version's icons, the Android launcher icons and the Android TV banner.
// Needs Playwright with Chromium: node scripts/render-icons.js   (then: convert ... for the Windows .ico, see below)
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const svg = fs.readFileSync(path.join(root, 'packaging/linux/cloudstream-linux.svg'), 'utf8');
const tile = /<path d="M112 0H400[^"]*" fill="#0b0b10"\/>/;
// Square (no rounded corners) for icons the system masks itself (iOS, Android legacy launchers).
const square = svg.replace(tile, '<rect width="512" height="512" fill="#0b0b10"/>');
const outputs = [
  ['packaging/linux/cloudstream-linux.png', 512, svg],
  ['app/src/main/resources/ui/icon.png', 256, svg],
  ['webapp/icon-192.png', 192, svg],
  ['webapp/icon-512.png', 512, svg],
  ['webapp/apple-touch-icon.png', 180, square],
  ['android/app/src/main/res/mipmap-mdpi/ic_launcher.png', 48, svg],
  ['android/app/src/main/res/mipmap-hdpi/ic_launcher.png', 72, svg],
  ['android/app/src/main/res/mipmap-xhdpi/ic_launcher.png', 96, svg],
  ['android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png', 144, svg],
  ['android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png', 192, svg],
];
const font = path.join(root, 'app/src/main/resources/ui/fonts/InterDisplay-ExtraBold.otf');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  for (const [file, size, art] of outputs) {
    await page.setViewportSize({ width: size, height: size });
    await page.setContent(`<html><body style="margin:0;background:transparent">${art.replace('<svg ', `<svg style="width:${size}px;height:${size}px;display:block" `)}</body></html>`);
    await page.screenshot({ path: path.join(root, file), omitBackground: true });
    console.log(`${file} ${size}x${size}`);
  }
  // Android TV banner (320x180 dp, xhdpi 640x360): the logo and the name.
  await page.setViewportSize({ width: 640, height: 360 });
  const fontCss = fs.existsSync(font) ? `@font-face{font-family:Brand;src:url(file://${font})}` : '';
  await page.setContent(`<html><head><style>${fontCss}
    body{margin:0;width:640px;height:360px;background:radial-gradient(circle at 30% 40%,#1a2a5c,#0b0b10 70%);display:flex;align-items:center;justify-content:center;gap:28px;font-family:Brand,Inter,sans-serif}
    h1{color:#fff;font-size:76px;line-height:.95;margin:0;letter-spacing:-1px}</style></head>
    <body>${square.replace('<svg ', '<svg style="width:190px;height:190px;border-radius:40px" ')}<h1>Stream<br>Goat</h1></body></html>`);
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(root, 'android/app/src/main/res/drawable-xhdpi/tv_banner.png') });
  console.log('android/app/src/main/res/drawable-xhdpi/tv_banner.png 640x360');
  await browser.close();
})();
