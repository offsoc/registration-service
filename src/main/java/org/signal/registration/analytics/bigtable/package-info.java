/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

@Configuration
@Requires(property = "analytics.bigtable.project-id")
@Requires(property = "analytics.bigtable.instance-id")
@Requires(property = "analytics.bigtable.table-id")
@Requires(property = "analytics.bigtable.column-family-name")
package org.signal.registration.analytics.bigtable;

import io.micronaut.context.annotation.Configuration;
import io.micronaut.context.annotation.Requires;