import test from 'node:test';
import assert from 'node:assert/strict';
import { effectiveIdentityTier } from '../.test-build/services/identity-tier.js';
import { userInfo } from '../.test-build/controllers/identity-view.js';

const now = Date.parse('2026-11-12T03:00:00Z');
test('expired subscriptions resolve to FREE, including the exact expiry instant', () => {
  for (const expiry of ['2026-10-02T01:31:27.000000Z', '2026-11-12 03:00:00', '2026-11-12T11:00:00+08:00', 'invalid']) {
    assert.equal(effectiveIdentityTier({ tier: 'PLUS', tierExpiresAt: expiry }, now), 'FREE');
  }
});
test('valid subscriptions and accounts with no expiry retain their tier', () => {
  assert.equal(effectiveIdentityTier({ tier: 'PLUS', tierExpiresAt: '2026-11-12T03:00:01Z' }, now), 'PLUS');
  assert.equal(effectiveIdentityTier({ tier: 'PRO', tierExpiresAt: null }, now), 'PRO');
});
test('the shared user view returns the effective tier while preserving the historical expiry', () => {
  const expired = { tier: 'PLUS', tierExpiresAt: '2000-10-02T01:31:27Z', createdAt: '2000-01-01T00:00:00Z' };
  const result = userInfo(expired);
  assert.equal(result.tier, 'FREE');
  assert.equal(result.tierExpiresAt, expired.tierExpiresAt);
  assert.equal(expired.tier, 'PLUS');
});
