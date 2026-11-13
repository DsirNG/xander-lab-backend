import assert from 'node:assert/strict';
import test from 'node:test';
import { allowsBlogTask } from '../.test-build/services/writing-policy.js';
for (const [text, expected] of [['写一篇关于春天的文章', false], ['帮我生成文字', false], ['用 Markdown 格式回答', false], ['直接给正文，不用博客', false], ['生成一个 Word 文件', false], ['给文章配两张插图', false], ['发布到当前博客', true], ['帮我写一篇博客', true]]) {
  test(`Writing delivery authorization: ${text}`, () => assert.equal(allowsBlogTask([{role:'user',content:text}]), expected));
}
test('Confirmation applies only to the immediately preceding delivery offer', () => {
  assert.equal(allowsBlogTask([{role:'assistant',content:'需要的话，可以做成博客并添加两张插图。'},{role:'user',content:'可以'}]), true);
  assert.equal(allowsBlogTask([{role:'assistant',content:'可以做成博客。'},{role:'assistant',content:'要继续解释这段吗？'},{role:'user',content:'可以'}]), false);
});
