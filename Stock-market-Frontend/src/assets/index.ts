interface AssetModule {
  default: string;
}

// Auto import all images & videos recursively
const assetModules = import.meta.glob<AssetModule>(
  "./**/*.{png,jpg,jpeg,svg,webp,mp4,webm,mov}",
  { eager: true },
);

const Assets: Record<string, string> = Object.fromEntries(
  Object.entries(assetModules).map(([path, module]) => {
    const key = path
      .replace("./", "")
      .replace(/\.(png|jpg|jpeg|svg|webp|mp4|webm|mov)$/, "")
      .replace(/\//g, "_");

    return [key, module.default];
  }),
);

export default Assets;
