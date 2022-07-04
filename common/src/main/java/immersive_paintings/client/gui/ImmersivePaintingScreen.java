package immersive_paintings.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import immersive_paintings.Config;
import immersive_paintings.Main;
import immersive_paintings.client.ClientUtils;
import immersive_paintings.client.gui.widget.*;
import immersive_paintings.cobalt.network.NetworkHandler;
import immersive_paintings.entity.ImmersivePaintingEntity;
import immersive_paintings.network.LazyNetworkManager;
import immersive_paintings.network.c2s.PaintingDeleteRequest;
import immersive_paintings.network.c2s.PaintingModifyRequest;
import immersive_paintings.network.c2s.RegisterPaintingRequest;
import immersive_paintings.network.c2s.UploadPaintingRequest;
import immersive_paintings.resources.*;
import immersive_paintings.util.FlowingText;
import immersive_paintings.util.ImageManipulations;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;

import static immersive_paintings.util.ImageManipulations.scanForPixelArtMultiple;
import static immersive_paintings.util.Utils.identifierToTranslation;

public class ImmersivePaintingScreen extends Screen {
    final int entityId;
    public final ImmersivePaintingEntity entity;

    private String filteredString = "";
    private int filteredResolution = 0;
    private int filteredWidth = 0;
    private int filteredHeight = 0;
    private final List<Identifier> filteredPaintings = new ArrayList<>();

    private int selectionPage;
    private Page page;

    private ButtonWidget pageWidget;

    private final List<PaintingWidget> paintingWidgetList = new LinkedList<>();
    private ByteImage currentImage;
    private static int currentImagePixelZoomCache = -1;
    private String currentImageName;
    private PixelatorSettings settings;
    private ByteImage pixelatedImage;

    private List<File> screenshots = List.of();
    private int screenshotPage;

    private Identifier deletePainting;
    private TranslatableText error;
    private boolean shouldReProcess;

    public ImmersivePaintingScreen(int entityId) {
        super(new TranslatableText("item.immersive_paintings.painting"));
        this.entityId = entityId;

        if (MinecraftClient.getInstance().world != null && MinecraftClient.getInstance().world.getEntityById(entityId) instanceof ImmersivePaintingEntity painting) {
            entity = painting;
        } else {
            entity = null;
        }

        if (entity == null) {
            close();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        super.init();

        clearSearch();
        setPage(Page.SELECTION_DATAPACKS);
        updateSearch();

        File file = new File(MinecraftClient.getInstance().runDirectory, "screenshots");
        File[] files = file.listFiles(v -> v.getName().endsWith(".png"));
        if (files != null) {
            screenshots = Arrays.stream(files).toList();
        }
    }

    private void clearSearch() {
        filteredString = "";
        filteredResolution = 0;
        filteredWidth = 0;
        filteredHeight = 0;
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        switch (page) {
            case NEW -> {
                fill(matrices, width / 2 - 100, height / 2 - 60, width / 2 + 100, height / 2 - 40, 0x10000000);
                List<Text> wrap = FlowingText.wrap(new TranslatableText("Drop an image here, enter a file path or URL or select a screenshot to start."), 220);
                int y = height / 2 - 40 - wrap.size() * 12;
                for (Text text : wrap) {
                    drawCenteredText(matrices, textRenderer, text, width / 2, y, 0xFFFFFFFF);
                    y += 12;
                }
            }
            case CREATE -> {
                if (shouldReProcess) {
                    pixelateImage();
                }

                int maxWidth = 190;
                int maxHeight = 135;
                int tw = settings.resolution * settings.width;
                int th = settings.resolution * settings.height;
                float size = Math.min((float)maxWidth / tw, (float)maxHeight / th);
                RenderSystem.setShader(GameRenderer::getPositionTexShader);
                RenderSystem.setShaderTexture(0, Main.locate("temp_pixelated"));
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.enableDepthTest();
                matrices.push();
                matrices.translate(width / 2.0f - tw * size / 2.0f, height / 2.0f - th * size / 2.0f, 0.0f);
                matrices.scale(size, size, 1.0f);
                drawTexture(matrices, 0, 0, 0, 0, tw, th, tw, th);
                matrices.pop();

                if (error != null) {
                    drawCenteredText(matrices, textRenderer, error, width / 2, height / 2, 0xFFFF0000);
                }
            }
            case DELETE -> {
                fill(matrices, width / 2 - 160, height / 2 - 50, width / 2 + 160, height / 2 + 50, 0x88000000);
                List<Text> wrap = FlowingText.wrap(new TranslatableText("immersive_paintings.confirm_deletion"), 300);
                int y = height / 2 - 35;
                for (Text t : wrap) {
                    drawCenteredText(matrices, textRenderer, t, width / 2, y, 0XFFFFFF);
                    y += 15;
                }
            }
            case LOADING -> {
                TranslatableText text = new TranslatableText("immersive_paintings.upload", (int)Math.ceil(LazyNetworkManager.getRemainingTime()));
                drawCenteredText(matrices, textRenderer, text, width / 2, height / 2, 0xFFFFFFFF);
            }
        }

        super.render(matrices, mouseX, mouseY, delta);
    }

    private List<Identifier> getMaterialsList() {
        return FrameLoader.frames.values().stream()
                .filter(v -> v.frame().equals(entity.getFrame()))
                .map(Frame::material)
                .distinct()
                .sorted(Identifier::compareTo)
                .toList();
    }


    private void rebuild() {
        clearChildren();

        // filters
        if (page != Page.CREATE) {
            addDrawableChild(new ButtonWidget(width / 2 - 50 - 150, height / 2 - 90 - 22, 80, 20, new TranslatableText("immersive_paintings.page.yours"), sender -> setPage(Page.SELECTION_YOURS))).active = page != Page.SELECTION_YOURS;
            addDrawableChild(new ButtonWidget(width / 2 - 50 - 70, height / 2 - 90 - 22, 80, 20, new TranslatableText("immersive_paintings.page.datapacks"), sender -> setPage(Page.SELECTION_DATAPACKS))).active = page != Page.SELECTION_DATAPACKS;
            addDrawableChild(new ButtonWidget(width / 2 - 50 + 10, height / 2 - 90 - 22, 80, 20, new TranslatableText("immersive_paintings.page.players"), sender -> setPage(Page.SELECTION_PLAYERS))).active = page != Page.SELECTION_PLAYERS;
            addDrawableChild(new ButtonWidget(width / 2 - 50 + 90, height / 2 - 90 - 22, 80, 20, new TranslatableText("immersive_paintings.page.new"), sender -> setPage(Page.NEW))).active = page != Page.NEW;
            addDrawableChild(new ButtonWidget(width / 2 - 50 + 170, height / 2 - 90 - 22, 80, 20, new TranslatableText("immersive_paintings.page.frame"), sender -> setPage(Page.FRAME))).active = page != Page.FRAME;
        }

        switch (page) {
            case NEW -> {
                //URL
                TextFieldWidget textFieldWidget = addDrawableChild(new TextFieldWidget(this.textRenderer, width / 2 - 90, height / 2 - 38, 180, 16,
                        new LiteralText("URL")));
                textFieldWidget.setMaxLength(1024);

                addDrawableChild(new ButtonWidget(width / 2 - 50, height / 2 - 15, 100, 20, new TranslatableText("immersive_paintings.load"), sender -> loadImage(textFieldWidget.getText())));

                //screenshots
                rebuildScreenshots();

                //screenshot page
                addDrawableChild(new ButtonWidget(width / 2 - 65, height / 2 + 70, 30, 20, new LiteralText("<<"), sender -> setScreenshotPage(screenshotPage - 1)));
                pageWidget = addDrawableChild(new ButtonWidget(width / 2 - 65 + 30, height / 2 + 70, 70, 20, new LiteralText(""), sender -> {
                }));
                addDrawableChild(new ButtonWidget(width / 2 - 65 + 100, height / 2 + 70, 30, 20, new LiteralText(">>"), sender -> setScreenshotPage(screenshotPage + 1)));
                setScreenshotPage(screenshotPage);
            }
            case CREATE -> {
                //name
                TextFieldWidget textFieldWidget = addDrawableChild(new TextFieldWidget(this.textRenderer, width / 2 - 90, height / 2 - 100, 180, 20,
                        new TranslatableText("immersive_paintings.name")));
                textFieldWidget.setMaxLength(256);
                textFieldWidget.setText(currentImageName);
                textFieldWidget.setChangedListener((s) -> currentImageName = s);

                int y = height / 2 - 60;

                //width
                addDrawableChild(new IntegerSliderWidget(width / 2 - 200, y, 100, 20, "immersive_paintings.width", settings.width, 1, 16, v -> {
                    settings.width = v;
                    shouldReProcess = true;
                }));
                y += 22;

                //height
                addDrawableChild(new IntegerSliderWidget(width / 2 - 200, y, 100, 20, "immersive_paintings.width", settings.height, 1, 16, v -> {
                    settings.height = v;
                    shouldReProcess = true;
                }));
                y += 22;

                //resolution
                int[] resolutions = new int[] {16, 32, 64, 128};
                int x = width / 2 - 200;
                List<ButtonWidget> list = new LinkedList<>();
                for (int res : resolutions) {
                    ButtonWidget widget = addDrawableChild(new TooltipButtonWidget(x, y, 25, 20,
                            new LiteralText(String.valueOf(res)),
                            new TranslatableText("immersive_paintings.tooltip.resolution"),
                            v -> {
                                settings.resolution = res;
                                if (settings.pixelArt) {
                                    adaptToPixelArt();
                                    refreshPage();
                                }
                                shouldReProcess = true;
                                list.forEach(b -> b.active = true);
                                v.active = false;
                            }));
                    widget.active = settings.resolution != res;
                    list.add(widget);
                    x += 25;
                }
                y += 22;
                y += 10;

                //color reduction
                addDrawableChild(new IntegerSliderWidget(width / 2 - 200, y, 100, 20, "%s colors", 12, 1, 25, v -> {
                    settings.colors = v;
                    shouldReProcess = true;
                })).active = !settings.pixelArt;
                y += 22;

                //dither
                addDrawableChild(new PercentageSliderWidget(width / 2 - 200, y, 100, 20, "%s%% dither", 0.25, v -> {
                    settings.dither = v;
                    shouldReProcess = true;
                })).active = !settings.pixelArt;

                //pixelArt
                y = height / 2 - 40;
                addDrawableChild(new CallbackCheckboxWidget(width / 2 + 100, y, 20, 20,
                        new TranslatableText("immersive_paintings.pixelart"),
                        new TranslatableText("immersive_paintings.pixelart.tooltip"),
                        settings.pixelArt, true, (b) -> {
                    settings.pixelArt = b;
                    adaptToPixelArt();
                    refreshPage();
                    shouldReProcess = true;
                }));
                y += 22;

                //offset X
                addDrawableChild(new PercentageSliderWidget(width / 2 + 100, y, 100, 20, "%s%% X offset", 0.5, v -> {
                    settings.offsetX = v;
                    shouldReProcess = true;
                }));
                y += 22;

                //offset Y
                addDrawableChild(new PercentageSliderWidget(width / 2 + 100, y, 100, 20, "%s%% Y offset", 0.5, v -> {
                    settings.offsetY = v;
                    shouldReProcess = true;
                }));
                y += 22;

                //offset
                addDrawableChild(new PercentageSliderWidget(width / 2 + 100, y, 100, 20, "%s%% zoom", 1, 1, 3, v -> {
                    settings.zoom = v;
                    shouldReProcess = true;
                })).active = !settings.pixelArt;

                addDrawableChild(new ButtonWidget(width / 2 - 85, height / 2 + 75, 80, 20, new TranslatableText("immersive_paintings.cancel"), v -> setPage(Page.NEW)));

                addDrawableChild(new ButtonWidget(width / 2 + 5, height / 2 + 75, 80, 20, new TranslatableText("immersive_paintings.save"),
                        v -> {
                            byte[] is = pixelatedImage.getBytes();
                            int splits = (int)Math.ceil((double)is.length / Config.getInstance().packetSize);
                            int split = 0;
                            for (int i = 0; i < is.length; i += Config.getInstance().packetSize) {
                                byte[] ints = Arrays.copyOfRange(is, i, Math.min(is.length, i + Config.getInstance().packetSize));
                                LazyNetworkManager.sendServer(new UploadPaintingRequest(pixelatedImage.getWidth(), pixelatedImage.getHeight(), ints, split, splits));
                                split++;
                            }

                            LazyNetworkManager.sendServer(new RegisterPaintingRequest(currentImageName, new Painting(
                                    pixelatedImage,
                                    settings.width,
                                    settings.height,
                                    settings.resolution
                            )));



                            setPage(Page.LOADING);
                        }));
            }
            case SELECTION_YOURS, SELECTION_DATAPACKS, SELECTION_PLAYERS -> {
                rebuildPaintings();

                // page
                addDrawableChild(new ButtonWidget(width / 2 - 35 - 30, height / 2 + 80, 30, 20, new LiteralText("<<"), sender -> setSelectionPage(selectionPage - 1)));
                pageWidget = addDrawableChild(new ButtonWidget(width / 2 - 35, height / 2 + 80, 70, 20, new LiteralText(""), sender -> {
                }));
                addDrawableChild(new ButtonWidget(width / 2 + 35, height / 2 + 80, 30, 20, new LiteralText(">>"), sender -> setSelectionPage(selectionPage + 1)));
                setSelectionPage(selectionPage);

                //search
                TextFieldWidget textFieldWidget = addDrawableChild(new TextFieldWidget(this.textRenderer, width / 2 - 65, height / 2 - 88, 130, 16,
                        new TranslatableText("immersive_paintings.search")));
                textFieldWidget.setMaxLength(64);
                textFieldWidget.setSuggestion("search");
                textFieldWidget.setChangedListener((s) -> {
                    filteredString = s;
                    updateSearch();
                    textFieldWidget.setSuggestion(null);
                });

                //filter resolution
                int[] resolutions = new int[] {0, 16, 32, 64, 128};
                int x = width / 2 - 200;
                List<ButtonWidget> list = new LinkedList<>();
                for (int res : resolutions) {
                    ButtonWidget widget = addDrawableChild(new TooltipButtonWidget(x, height / 2 - 90, 25, 20,
                            res == 0 ? new TranslatableText("immersive_paintings.filter.all") : new LiteralText(String.valueOf(res)),
                            new TranslatableText("immersive_paintings.tooltip.filter_resolution"),
                            v -> {
                                filteredResolution = res;
                                updateSearch();
                                list.forEach(b -> b.active = true);
                                v.active = false;
                            }));
                    widget.active = res != filteredResolution;
                    list.add(widget);
                    x += 25;
                }

                //width
                TextFieldWidget widthWidget = addDrawableChild(new TextFieldWidget(this.textRenderer, width / 2 + 80, height / 2 - 88, 40, 16,
                        new TranslatableText("immersive_paintings.filter_width")));
                widthWidget.setMaxLength(2);
                widthWidget.setSuggestion("width");
                widthWidget.setChangedListener((s) -> {
                    try {
                        filteredWidth = Integer.parseInt(s);
                        updateSearch();
                    } catch (NumberFormatException ignored) {
                        filteredWidth = 0;
                    }
                    widthWidget.setSuggestion(null);
                });

                //height
                TextFieldWidget heightWidget = addDrawableChild(new TextFieldWidget(this.textRenderer, width / 2 + 80 + 40, height / 2 - 88, 40, 16,
                        new TranslatableText("immersive_paintings.filter_height")));
                heightWidget.setMaxLength(2);
                heightWidget.setSuggestion("height");
                heightWidget.setChangedListener((s) -> {
                    try {
                        filteredHeight = Integer.parseInt(s);
                        updateSearch();
                    } catch (NumberFormatException ignored) {
                        filteredHeight = 0;
                    }
                    heightWidget.setSuggestion(null);
                });
            }
            case FRAME -> {
                //frame
                int y = height / 2 - 80;
                List<Identifier> frames = FrameLoader.frames.values().stream().map(Frame::frame).distinct().sorted(Identifier::compareTo).toList();
                for (Identifier frame : frames) {
                    ButtonWidget widget = addDrawableChild(new ButtonWidget(width / 2 - 200, y, 100, 20, new TranslatableText("immersive_paintings.frame." + identifierToTranslation(frame)), v -> {
                        entity.setFrame(frame);
                        entity.setMaterial(getMaterialsList().get(0));
                        NetworkHandler.sendToServer(new PaintingModifyRequest(entity));
                        setPage(Page.FRAME);
                    }));
                    widget.active = !frame.equals(entity.getFrame());
                    y += 25;
                }

                //material
                int py = 0;
                int px = 0;
                List<Identifier> materials = getMaterialsList();
                List<ButtonWidget> materialList = new LinkedList<>();
                for (Identifier material : materials) {
                    ButtonWidget widget = addDrawableChild(new TexturedButtonWidget(
                            width / 2 - 80 + px * 65,
                            height / 2 - 80 + py * 20,
                            64,
                            16,
                            new Identifier(material.getNamespace(), material.getPath().replace("/block/", "/gui/")),
                            0,
                            0,
                            64,
                            32,
                            new LiteralText(""),
                            v -> {
                                entity.setMaterial(material);
                                NetworkHandler.sendToServer(new PaintingModifyRequest(entity));
                                materialList.forEach(b -> b.active = true);
                                v.active = false;
                            },
                            (ButtonWidget b, MatrixStack matrixStack, int mx, int my) -> renderTooltip(matrixStack, new TranslatableText("immersive_paintings.material." + identifierToTranslation(material)), mx, my)));
                    widget.active = !material.equals(entity.getMaterial());
                    materialList.add(widget);

                    px++;
                    if (px > 3) {
                        px = 0;
                        py++;
                    }
                }

                addDrawableChild(new ButtonWidget(width / 2 - 50, height / 2 + 70, 100, 20, new TranslatableText("immersive_paintings.done"), v -> close()));
            }
            case DELETE -> {
                addDrawableChild(new ButtonWidget(width / 2 - 100 - 5, height / 2 + 20, 100, 20, new TranslatableText("immersive_paintings.cancel"), v -> setPage(Page.SELECTION_YOURS)));

                addDrawableChild(new ButtonWidget(width / 2 + 5, height / 2 + 20, 100, 20, new TranslatableText("immersive_paintings.delete"), v -> {
                    NetworkHandler.sendToServer(new PaintingDeleteRequest(deletePainting));
                    setPage(Page.SELECTION_YOURS);
                }));
            }
        }
    }

    private void rebuildPaintings() {
        for (PaintingWidget w : paintingWidgetList) {
            remove(w);
        }
        paintingWidgetList.clear();

        // paintings
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 8; x++) {
                int i = y * 8 + x + selectionPage * 24;
                if (i >= 0 && i < filteredPaintings.size()) {
                    Identifier identifier = filteredPaintings.get(i);
                    Painting painting = ClientPaintingManager.getPainting(identifier);

                    //tooltip
                    List<Text> tooltip = new LinkedList<>();
                    tooltip.add(new LiteralText(painting.name));
                    tooltip.add(new TranslatableText("immersive_paintings.by_author", painting.author).formatted(Formatting.ITALIC));
                    tooltip.add(new TranslatableText("immersive_paintings.resolution", painting.width, painting.height, painting.resolution).formatted(Formatting.ITALIC));

                    if (page == Page.SELECTION_YOURS) {
                        tooltip.add(new TranslatableText("immersive_paintings.right_click_to_delete").formatted(Formatting.ITALIC).formatted(Formatting.GRAY));
                    }

                    paintingWidgetList.add(addDrawableChild(new PaintingWidget(ClientPaintingManager.getPaintingTexture(identifier, Painting.Type.THUMBNAIL), (int)(width / 2 + (x - 3.5) * 48) - 24, height / 2 - 66 + y * 48, 46, 46,
                            sender -> {
                                entity.setMotive(identifier);
                                NetworkHandler.sendToServer(new PaintingModifyRequest(entity));
                                setPage(Page.FRAME);
                            },
                            (b) -> {
                                if (page == Page.SELECTION_YOURS) {
                                    deletePainting = identifier;
                                    setPage(Page.DELETE);
                                }
                            },
                            (ButtonWidget b, MatrixStack matrices, int mx, int my) -> renderTooltip(matrices, tooltip, mx, my))));
                } else {
                    break;
                }
            }
        }
    }

    private void rebuildScreenshots() {
        for (PaintingWidget w : paintingWidgetList) {
            remove(w);
        }
        paintingWidgetList.clear();

        // screenshots
        for (int x = 0; x < 6; x++) {
            int i = x + screenshotPage * 6;
            if (i >= 0 && i < screenshots.size()) {
                File file = screenshots.get(i);
                ByteImage image = loadImage(file.getPath(), Main.locate("screenshot_" + x));
                if (image != null) {
                    Painting painting = new Painting(image, 16);
                    painting.thumbnail.textureIdentifier = Main.locate("screenshot_" + x);
                    paintingWidgetList.add(addDrawableChild(new PaintingWidget(painting.thumbnail, (int)(width / 2 + (x - 2.5) * 68) - 32, height / 2 + 15, 64, 48,
                            (b) -> {
                                currentImage = image;
                                currentImagePixelZoomCache = -1;
                                currentImageName = file.getName();
                                settings = new PixelatorSettings(currentImage);
                                setPage(Page.CREATE);
                                pixelateImage();
                            },
                            (b) -> {

                            },
                            (ButtonWidget b, MatrixStack matrices, int mx, int my) -> renderTooltip(matrices, new LiteralText(file.getName()), mx, my))));
                } else {
                    break;
                }
            }
        }
    }

    public void setPage(Page page) {
        this.page = page;
        this.error = null;

        if (page == Page.SELECTION_DATAPACKS) {
            filteredResolution = 32;
        } else {
            filteredResolution = 0;
        }

        rebuild();

        if (page == Page.SELECTION_DATAPACKS || page == Page.SELECTION_PLAYERS || page == Page.SELECTION_YOURS) {
            updateSearch();
        }
    }

    private void updateSearch() {
        filteredPaintings.clear();

        String playerName = MinecraftClient.getInstance().player == null ? "" : MinecraftClient.getInstance().player.getGameProfile().getName();
        filteredPaintings.addAll(ClientPaintingManager.getPaintings().entrySet().stream()
                .filter(v -> page != Page.SELECTION_YOURS || Objects.equals(v.getValue().author, playerName) && !v.getValue().datapack)
                .filter(v -> page != Page.SELECTION_PLAYERS || !Objects.equals(v.getValue().author, playerName) && !v.getValue().datapack)
                .filter(v -> page != Page.SELECTION_DATAPACKS || v.getValue().datapack)
                .filter(v -> v.getKey().toString().contains(filteredString))
                .filter(v -> filteredResolution == 0 || v.getValue().resolution == filteredResolution)
                .filter(v -> filteredWidth == 0 || v.getValue().width == filteredWidth)
                .filter(v -> filteredHeight == 0 || v.getValue().height == filteredHeight)
                .map(Map.Entry::getKey)
                .toList());

        setSelectionPage(selectionPage);
    }

    private void setSelectionPage(int p) {
        selectionPage = Math.min(getMaxPages() - 1, Math.max(0, p));
        rebuildPaintings();
        pageWidget.setMessage(new LiteralText((selectionPage + 1) + " / " + getMaxPages()));
    }

    private int getMaxPages() {
        return (int)Math.ceil(filteredPaintings.size() / 24.0);
    }

    private void setScreenshotPage(int p) {
        screenshotPage = Math.min(getScreenshotMaxPages() - 1, Math.max(0, p));
        rebuildScreenshots();
        pageWidget.setMessage(new LiteralText((screenshotPage + 1) + " / " + getScreenshotMaxPages()));
    }

    private int getScreenshotMaxPages() {
        return (int)Math.ceil(screenshots.size() / 8.0);
    }

    @Override
    public void filesDragged(List<Path> paths) {
        Path path = paths.get(0);
        loadImage(path.toString());
    }

    private void loadImage(String path) {
        currentImage = loadImage(path, Main.locate("temp"));
        currentImagePixelZoomCache = -1;
        if (currentImage != null) {
            currentImageName = Path.of(path).getFileName().toString().replaceFirst("[.][^.]+$", "");
            settings = new PixelatorSettings(currentImage);
            setPage(Page.CREATE);
            pixelateImage();
        }
    }

    private ByteImage loadImage(String path, Identifier identifier) {
        InputStream stream = null;
        try {
            stream = new URL(path).openStream();
        } catch (Exception exception) {
            try {
                stream = new FileInputStream(path);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (stream != null) {
            try {
                ByteImage nativeImage = ByteImage.read(stream);
                MinecraftClient.getInstance().getTextureManager().registerTexture(identifier, new NativeImageBackedTexture(ClientUtils.byteImageToNativeImage(nativeImage)));
                stream.close();
                return nativeImage;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return null;
    }

    private static int getCurrentImagePixelZoomCache(ByteImage currentImage) {
        if (currentImagePixelZoomCache < 0) {
            currentImagePixelZoomCache = scanForPixelArtMultiple(currentImage);
        }
        return currentImagePixelZoomCache;
    }

    private void pixelateImage() {
        pixelatedImage = pixelateImage(currentImage, settings);
        MinecraftClient.getInstance().getTextureManager().registerTexture(Main.locate("temp_pixelated"), new NativeImageBackedTexture(ClientUtils.byteImageToNativeImage(pixelatedImage)));
    }

    private void adaptToPixelArt() {
        double zoom = getCurrentImagePixelZoomCache(currentImage);
        settings.width = Math.min(16, (int)(currentImage.getWidth() / zoom / settings.resolution));
        settings.height = Math.min(16, (int)(currentImage.getHeight() / zoom / settings.resolution));
    }

    public static ByteImage pixelateImage(ByteImage currentImage, PixelatorSettings settings) {
        ByteImage pixelatedImage = new ByteImage(settings.resolution * settings.width, settings.resolution * settings.height);

        //zoom
        double zoom;
        if (settings.pixelArt) {
            zoom = getCurrentImagePixelZoomCache(currentImage);
        } else {
            float fx = (float)currentImage.getWidth() / pixelatedImage.getWidth();
            float fy = (float)currentImage.getHeight() / pixelatedImage.getHeight();
            zoom = Math.min(fx, fy) / settings.zoom;
        }

        //offset
        int ox = (int)((currentImage.getWidth() - pixelatedImage.getWidth() * zoom) * settings.offsetX);
        int oy = (int)((currentImage.getHeight() - pixelatedImage.getHeight() * zoom) * settings.offsetY);
        if (settings.pixelArt) {
            ox = ox / ((int)zoom) * ((int)zoom);
            oy = oy / ((int)zoom) * ((int)zoom);
        }

        //downscale
        ImageManipulations.resize(pixelatedImage, currentImage, zoom, ox, oy);

        //dither
        if (settings.dither > 0 && !settings.pixelArt) {
            if (settings.colors > 1) {
                ImageManipulations.dither(pixelatedImage, settings.dither / settings.colors);
            } else {
                ImageManipulations.dither(pixelatedImage, settings.dither / 16.0);
            }
        }

        //reduce colors
        if (settings.colors > 1 && !settings.pixelArt) {
            ImageManipulations.reduceColors(pixelatedImage, settings.colors);
        }

        return pixelatedImage;
    }

    public void refreshPage() {
        setPage(page);
    }

    public void setError(TranslatableText text) {
        this.error = text;
    }

    public enum Page {
        SELECTION_YOURS,
        SELECTION_DATAPACKS,
        SELECTION_PLAYERS,
        NEW,
        CREATE,
        FRAME,
        DELETE,
        LOADING
    }

    public static final class PixelatorSettings {
        public double dither;
        public int colors;
        public int resolution;
        public int width;
        public int height;
        public double offsetX;
        public double offsetY;
        public double zoom;
        public boolean pixelArt;

        public PixelatorSettings(double dither, int colors, int resolution, int width, int height, double offsetX, double offsetY, double zoom, boolean pixelArt) {
            this.dither = dither;
            this.colors = colors;
            this.resolution = resolution;
            this.width = width;
            this.height = height;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.zoom = zoom;
            this.pixelArt = pixelArt;
        }

        PixelatorSettings(ByteImage currentImage) {
            this(0.25, 10, 32, 1, 1, 0.5, 0.5, 1, false);

            double target = currentImage.getWidth() / (double)currentImage.getHeight();
            double bestScore = 100;

            double d = Math.sqrt(currentImage.getWidth() * currentImage.getWidth() + currentImage.getHeight() * currentImage.getHeight());
            double dw = currentImage.getWidth() / d;
            double dh = currentImage.getHeight() / d;
            for (float diagonal = 3.0f; diagonal < 8.0; diagonal += target) {
                int pw = (int)(dw * diagonal + 0.5);
                int ph = (int)(dh * diagonal + 0.5);
                double e = Math.abs(pw / (double)ph - target) * Math.sqrt(5 + width + height);
                if (e < bestScore) {
                    width = pw;
                    height = ph;
                    bestScore = e;
                }
            }
        }
    }
}
