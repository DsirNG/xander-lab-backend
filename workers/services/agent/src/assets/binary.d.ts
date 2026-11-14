declare module "*.ttf" {
  const bytes: ArrayBuffer;
  export default bytes;
}
declare module "@pdf-lib/fontkit/dist/fontkit.umd.js" {
  import * as fontkit from "@pdf-lib/fontkit";
  export default fontkit;
}
