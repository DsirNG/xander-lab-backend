import * as nodeUtil from "util";
export * from "util";

// SDK debug messages contain signed forms and raw provider responses. Workers'
// debuglog shim may emit them without NODE_DEBUG, so this Worker suppresses them.
export const debuglog = () => Object.assign(() => {}, { enabled: false });
export default { ...nodeUtil, debuglog };
