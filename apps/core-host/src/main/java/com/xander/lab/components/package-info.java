/**
 * Component library: curated, shareable UI components.
 *
 * <p>The module owns the component catalog (categories, items, scenarios and
 * detail pages) and community-shared submissions. It does not own articles,
 * publication, studio projects, or user identity.</p>
 *
 * <p>Reading a contributor's display name goes through
 * {@code com.xander.lab.core.api.UserLookupPort}, never through identity's
 * {@code UserMapper} or {@code User} entity. Injecting the mapper — which is
 * what this module used to do — makes {@code sys_user} effectively shared, so
 * a column rename would break this module with no compile error. The fallback
 * text ("匿名用户{id}") stays here: it is this module's display policy, not
 * identity's data.</p>
 */
package com.xander.lab.components;