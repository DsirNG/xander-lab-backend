/**
 * Domain model of the billing module: point account and ledger entries.
 *
 * <p>{@code BillingBiz} — the biz_type catalogue — used to live here and has moved to the
 * contract {@code com.xander.lab.payment.api.BillingBiz} (V9 §18 / §91). It is not an internal
 * enum: every charging call site has to name what it charges for, and those call sites sit in
 * six domains across three future deployment units, so the vocabulary is contract surface.
 * What stays here is persistence and behaviour — {@code PointAccount}, {@code PointsLedger},
 * {@code ModelPrice}.</p>
 */
package com.xander.lab.billing.domain;
