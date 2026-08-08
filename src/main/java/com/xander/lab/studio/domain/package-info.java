/**
 * FlowCraft project, canvas and snapshot models, plus the image-to-3D task
 * model.
 *
 * <p>Owns the {@code FlowProject → FlowCanvas → FlowSnapshot} chain and
 * {@code Img2ThreeTask}. Domain state must not depend on Spring MVC, HTTP
 * clients, or vendor SDKs.</p>
 */
package com.xander.lab.studio.domain;