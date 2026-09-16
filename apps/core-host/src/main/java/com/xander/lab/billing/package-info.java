/**
 * Points and billing domain.
 *
 * <p>The module owns the per-user point account, the audit ledger and the
 * token/flat pricing used to meter paid resources. It does not own users, task
 * generation, agents, studio pipelines or publication. Feature modules call
 * the billing application service to pre-check and charge; they must not query
 * billing tables directly.</p>
 */
package com.xander.lab.billing;