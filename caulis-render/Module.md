# Module caulis-render

Drawing on the GPU. The rest of Caulis only talks to [dev.caulis.render.Renderer], so the
technology behind it can change (OpenGL ES today, Vulkan later).

# Package dev.caulis.render

The renderer interface, GPU pictures, shared GPU buffers (dma-buf), colours, and the
drawing behind window effects: rounded clipping, borders, shadows and blur.

# Package dev.caulis.render.gles

The OpenGL ES implementation (on EGL and GBM, no window system needed).
