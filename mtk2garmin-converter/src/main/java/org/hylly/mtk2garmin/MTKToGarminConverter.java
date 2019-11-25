package org.hylly.mtk2garmin;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.shorts.Short2ShortMap;
import it.unimi.dsi.fastutil.shorts.Short2ShortRBTreeMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.gdal.ogr.*;
import org.gdal.osr.CoordinateTransformation;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class MTKToGarminConverter {
    Logger logger = Logger.getLogger(MTKToGarminConverter.class.getName());

    private final Object2ObjectRBTreeMap<String, double[]> gridExtents = new Object2ObjectRBTreeMap<>();
    private final double COORD_DELTA_X = 62000.0 - 6e3;
    private final double COORD_DELTA_Y = 6594000.0;

    private short tyyppi_string_id;
    private final Int2ObjectMap<Long2LongAVLTreeMap> nodepos;


    private Driver memoryd;

    private CoordinateTransformation srctowgs;
    private double minx = Double.POSITIVE_INFINITY, miny = Double.POSITIVE_INFINITY, maxx = Double.NEGATIVE_INFINITY,
            maxy = Double.NEGATIVE_INFINITY;


    private final FeatureIDProvider featureIDProvider = new FeatureIDProvider();

    private OSMPBF op;

    private final Config conf;

    private MMLFeaturePreprocess featurePreprocessMML;
    private ShapeFeaturePreprocess shapePreprocessor;
    private final GeomUtils geomUtils;
    private CachedAdditionalDataSources cachedDatasources;


    void doConvert() {
        File mtkDirectory = new File(conf.getString("maastotietokanta"));
        if (!mtkDirectory.exists()) {
            throw new IllegalArgumentException("Maastotietokanta directory does not exists");
        }

        Stream<File> files = getMTKCellFiles(mtkDirectory);

        Map<String, List<File>> areas = files.collect(Collectors.groupingBy(
                file -> file.getName().substring(0, 4),
                Collectors.toList()
        ));


        Path outdir = Paths.get(conf.getString("output"));

        if (!Files.exists(outdir)) {
            try {
                Files.createDirectories(outdir);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        }

        initializeCachedDatasources();

        areas
                .entrySet()
                .stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .filter(e -> e.getKey().startsWith("L434"))
                .map(Map.Entry::getValue)
                .forEach(areaCells -> {
                    areaCells.parallelStream().forEach(cellFile -> {
                        logger.info("Processing file: " + cellFile.toString() + " in thread [" + Thread.currentThread().getId() + "]");
                        try {
                            SingleCellConverter cellConverter = new SingleCellConverter(cellFile, outdir, conf, gridExtents, featurePreprocessMML, shapePreprocessor, geomUtils, featureIDProvider, cachedDatasources);
                            cellConverter.doConvert();
                        } catch (IOException e) {
                            logger.severe("Converting file " + cellFile + " failed. Exception: " + e.toString());
                            e.printStackTrace();
                        }
                    });
                });

    }

    private void initializeCachedDatasources() {
        cachedDatasources = new CachedAdditionalDataSources(conf, geomUtils);
    }

    private Stream<File> getMTKCellFiles(File mtkDirectory) {
        Collection<File> files = FileUtils.listFiles(
                mtkDirectory,
                new RegexFileFilter("^([A-Z0-9]{6})_mtk.zip"),
                DirectoryFileFilter.DIRECTORY
        );
        return files.stream().sorted();
    }

    MTKToGarminConverter(File configFile) {
        Int2ObjectAVLTreeMap<Long2LongAVLTreeMap> nodeposUnsafe = new Int2ObjectAVLTreeMap<>();
        nodepos = Int2ObjectMaps.synchronize(nodeposUnsafe);

        conf = readConfigFile(configFile);
        initializeOGR();
        readGridExtents();
        geomUtils = new GeomUtils();
    }

    private Config readConfigFile(File configFile) {
        logger.info("Reading config");
        Config conf = ConfigFactory.parseFile(configFile);
        logger.info("CONFIG: " + conf.root().render());
        return conf;

    }

    private void initializeOGR() {
        logger.info("Initializing ogr");
        ogr.UseExceptions();
        Locale.setDefault(new Locale("en", "US"));
        ogr.RegisterAll();

        memoryd = ogr.GetDriverByName("memory");

        featurePreprocessMML = new MMLFeaturePreprocess();
        shapePreprocessor = new ShapeFeaturePreprocess();
    }

    private void readGridExtents() {
        DataSource gridds = ogr.Open(conf.getString("grid"));
        Layer lyr = gridds.GetLayer(0);

        lyr.SetAttributeFilter("gridSize = '12x12'");

        double[] extent = new double[4];
        FeatureDefn glyrdef = lyr.GetLayerDefn();
        int gridcellIndex = glyrdef.GetFieldIndex("gridCell");

        for (Feature feat = lyr.GetNextFeature(); feat != null; feat = lyr.GetNextFeature()) {
            Geometry geom = feat.GetGeometryRef();

            geom.GetEnvelope(extent);
            gridExtents.put(feat.GetFieldAsString(gridcellIndex), extent.clone());
            feat.delete();
        }
        lyr.delete();
        gridds.delete();
    }

    static void printTags(StringTable stringtable, Short2ShortRBTreeMap tags) {
        for (Short2ShortMap.Entry t : tags.short2ShortEntrySet()) {
            System.out.println(stringtable.getStringById(t.getShortKey()) + " => " + stringtable.getStringById(t.getShortValue()));
        }
    }

    /*
        public static void main(String[] args) throws Exception {

            ShapeRetkeilyTagHandler retkeilyTagHandler;
            ShapeSyvyysTagHandler syvyysTagHandler;
            MMLTagHandler tagHandlerMML;
            StringTable stringtable;





            for (String area : areassorted) {
                ArrayList<File> files = areas.get(area);
                String[] filenames = new String[files.size()];
                int i = 0;
                for (File f : files) {

                    filenames[i] = f.getAbsolutePath();
                    i++;
                }

                Arrays.sort(filenames);

                for (String fn : filenames) {
                    String cell = fn.substring(fn.lastIndexOf(File.separator) + 1, fn.lastIndexOf(File.separator) + 7);
                    String cellWithoutLetter = cell.substring(0, cell.length() - 1);
                    String cellLetter = cell.substring(cell.length() - 1);

                    stringtable = new StringTable();
                    tyyppi_string_id = stringtable.getStringId("tyyppi");
                    tagHandlerMML = new MMLTagHandler(stringtable);
                    retkeilyTagHandler = new ShapeRetkeilyTagHandler(stringtable);
                    syvyysTagHandler = new ShapeSyvyysTagHandler(stringtable);
                    mtk2g.startWritingOSMPBF(Paths.get(outdir.toString(), String.format("%s.osm.pbf", cell)).toString());

                    System.out.println(fn + " (" + cell + " / " + cellWithoutLetter + " / " + cellLetter + ")");

                    double[] mml_extent = gridExtents.get(cell);
                    long st;


                    st = System.nanoTime();
                    DataSource mtkds = mtk2g.readOGRsource(stringtable, mtk2g.startReadingOGRFile("/vsizip/" + fn), featurePreprocessMML, tagHandlerMML, true, null);
                    mtkds.delete();
                    System.out.println("mtk read " + (System.nanoTime() - st) / 1000000000.0);
                    mtk2g.printCounts();
                    System.out.println(Arrays.toString(mml_extent));


                    st = System.nanoTime();
                    File cellKrkPath = new File(Paths.get(conf.getString("kiinteistorajat"), area.substring(0, 3)).toString());
                    File[] krkFiles = cellKrkPath.listFiles();

                    if (krkFiles != null) {
                        for (File krkf : krkFiles) {
                            String krkfn = krkf.getName();
                            if (!krkfn.startsWith(cellWithoutLetter)) continue;
                            String krkCell = krkfn.substring(krkfn.lastIndexOf(File.separator) + 1, krkfn.lastIndexOf(File.separator) + 7);
                            String krkCellLetter = krkCell.substring(krkCell.length() - 1);

                            if ("L".equals(cellLetter) && !leftLetters.contains(krkCellLetter)) continue;
                            if ("R".equals(cellLetter) && !rightLetters.contains(krkCellLetter)) continue;


                            System.out.println(krkCell + " / " + krkCellLetter);
                            System.out.println(krkf.getAbsolutePath());
                            DataSource krkds = mtk2g.readOGRsource(stringtable, mtk2g.startReadingOGRFile("/vsizip/" + krkf.getAbsolutePath() + "/" + krkCell + "_kiinteistoraja.shp"), shapePreprocessor, tagHandlerMML, false, mml_extent);
                            krkds.delete();
                        }
                        System.out.println("krk read " + (System.nanoTime() - st) / 1000000000.0);
                        mtk2g.printCounts();
                        System.out.println(Arrays.toString(mml_extent));
                    } else {
                        System.out.println("No krk exists for " + cell);
                    }


                    st = System.nanoTime();
                    mtk2g.readOGRsource(stringtable, syvyyskayrat, shapePreprocessor, syvyysTagHandler, false, mml_extent);
                    mtk2g.printCounts();
                    System.out.println("Syvyyskayrat read " + (System.nanoTime() - st) / 1000000000.0);

                    st = System.nanoTime();
                    mtk2g.readOGRsource(stringtable, syvyyspisteet, shapePreprocessor, syvyysTagHandler, false, mml_extent);
                    mtk2g.printCounts();
                    System.out.println("Syvyyspisteet read " + (System.nanoTime() - st) / 1000000000.0);

                    st = System.nanoTime();
                    mtk2g.readOGRsource(stringtable, kesaretkeily, shapePreprocessor, retkeilyTagHandler, false, mml_extent);
                    mtk2g.printCounts();
                    System.out.println("Kesaretkeilyreitit read " + (System.nanoTime() - st) / 1000000000.0);

                    st = System.nanoTime();
                    mtk2g.readOGRsource(stringtable, ulkoilureitit, shapePreprocessor, retkeilyTagHandler, false, mml_extent);
                    mtk2g.printCounts();
                    System.out.println("Ulkoilureitit read " + (System.nanoTime() - st) / 1000000000.0);

                    st = System.nanoTime();
                    mtk2g.readOGRsource(stringtable, luontopolut, shapePreprocessor, retkeilyTagHandler, false, mml_extent);
                    mtk2g.printCounts();
                    System.out.println("Luontopolut read " + (System.nanoTime() - st) / 1000000000.0);

                    st = System.nanoTime();
                    mtk2g.readOGRsource(stringtable, metsapoints, shapePreprocessor, retkeilyTagHandler, false, mml_extent);
                    mtk2g.printCounts();
                    System.out.println("Point_dump read " + (System.nanoTime() - st) / 1000000000.0);

                    st = System.nanoTime();
                    mtk2g.writeOSMPBFElements(stringtable);
                    mtk2g.closeOSMPBFFile();
                    mtk2g.trackCounts();
                    mtk2g.initElements();
                    System.out.println("pbf " + (System.nanoTime() - st) / 1000000000.0);

                }


                if (mtk2g.nodepos.size() > 0) {
                    System.out.println(area + " done!");
                    System.out.println(mtk2g.nodepos.size() + " cells");
                    long numids = 0;
                    for (Int2ObjectMap.Entry<Long2ObjectAVLTreeMap<Node>> ntree : mtk2g.nodepos.int2ObjectEntrySet()) {
                        numids += ntree.getValue().size();
                    }
                    System.out.println(numids + " ids in cache");
                }
            }
            System.exit(0);

        }

        private void initElements() {
            this.nodes.clear();
            this.nodes.trim();
            this.ways.clear();
            this.nodes.trim();
            this.relations.clear();
            this.relations.trim();
        }
    */


    /*

        private boolean nodeNearCellBorder(double[] srcpoints) {
            double dist = this.calculateMinNodeCellBorderDistance(srcpoints[0], srcpoints[1]);
            return Math.abs(dist) < 5;

        }

        private double calculateMinNodeCellBorderDistance(double x, double y) {
            return Math.min(Math.abs(this.llx - x),
                    Math.min(Math.abs(this.lly - y), Math.min(Math.abs(this.urx - x), Math.abs(this.ury - y))));
        }


        private void writeOSMXMLTags(StringTable stringtable, OutputStream fos, Short2ShortRBTreeMap nodeTags) throws IOException {
            if (nodeTags.size() == 0)
                return;

            for (Entry k : nodeTags.short2ShortEntrySet()) {
                fos.write(
                        String.format("\t\t<tag k=\"%s\" v=\"%s\" />\n", stringtable.getStringById(k.getShortKey()),
                                stringtable.getStringById(k.getShortValue())).getBytes());
            }
        }

        @SuppressWarnings("unused")
        private void writePlainOSMXML(StringTable stringtable, String ofn) throws IOException {
            BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(ofn));

            this.writeOSMXML(stringtable, fos);
        }

        @SuppressWarnings("unused")
        private void writeGzOSMXML(StringTable stringtable, String ofn) throws IOException {
            GZIPOutputStream fos = new GZIPOutputStream(new FileOutputStream(ofn));
            this.writeOSMXML(stringtable, fos);
        }

        private void writeOSMXML(StringTable stringtable, OutputStream fos) throws IOException {

            fos.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n".getBytes());
            fos.write("<osm version=\"0.6\" generator=\"mtk2garmin 0.0.1\">\n".getBytes());

            fos.write(String
                    .format("\t<bounds minlon=\"%f\" minlat=\"%f\" maxlon=\"%f\" maxlat=\"%f\"/>\n", minx, miny, maxx, maxy)
                    .getBytes());

            ObjectArrayList<Long2ObjectMap.Entry<Node>> node_keys_sorted = new ObjectArrayList<>();

            node_keys_sorted.addAll(nodes.long2ObjectEntrySet());

            node_keys_sorted.sort((k1, k2) -> (int) (k1.getValue().getId() - k2.getValue().getId()));

            for (Long2ObjectMap.Entry<Node> nk : node_keys_sorted) {
                Node n = nk.getValue();
                fos.write(String.format("\t\t<node id=\"%d\" visible=\"true\" version=\"1\" lat=\"%f\" lon=\"%f\"",
                        n.getId(), n.getLat(), n.getLon()).getBytes());

                if (n.nodeTags.size() > 0) {

                    fos.write(">\n".getBytes());
                    this.writeOSMXMLTags(stringtable, fos, n.nodeTags);
                    fos.write("\t</node>\n".getBytes());

                } else {
                    fos.write(" />\n".getBytes());
                }
            }
            long[] waykeys = ways.keySet().toLongArray();
            Arrays.sort(waykeys);

            for (long wk : waykeys) {
                Way w = ways.get(wk);
                fos.write(String.format("\t<way id=\"%d\" visible=\"true\" version=\"1\">\n", w.getId()).getBytes());

                for (int i = 0; i < w.refs.size(); i++) {
                    fos.write(String.format("\t\t<nd ref=\"%d\" />\n", w.refs.getLong(i)).getBytes());
                }
                this.writeOSMXMLTags(stringtable, fos, w.tags);
                fos.write("\t</way>\n".getBytes());
            }
            long[] relkeys = relations.keySet().toLongArray();
            Arrays.sort(relkeys);

            for (long rk : relkeys) {
                Relation r = relations.get(rk);
                fos.write(String.format("\t<relation id=\"%d\" version=\"1\" visible=\"true\">\n", r.getId()).getBytes());
                for (RelationMember m : r.members) {
                    fos.write(String.format("\t\t<member type=\"%s\" ref=\"%d\" role=\"%s\"/>\n",
                            new Object[]{m.getType(), Long.valueOf(m.getId()), m.getRole()}).getBytes());
                }
                this.writeOSMXMLTags(stringtable, fos, r.tags);
                fos.write("\t</relation>\n".getBytes());
            }
            fos.write("</osm>".getBytes());

            fos.close();
        }

        private void startWritingOSMPBF(String ofn) throws IOException {
            op = new OSMPBF();
            op.writePBFHeaders(ofn);
        }

        private void writeOSMPBFElements(StringTable stringtable) throws IOException {

            op.writePBFElements(stringtable, Boolean.FALSE, nodes, null, null);
            op.writePBFElements(stringtable, Boolean.FALSE, null, ways, null);
            op.writePBFElements(stringtable, Boolean.FALSE, null, null, relations);

            // this.initElements();
        }

        private void closeOSMPBFFile() throws IOException {
            op.closePBF();
        }

        @SuppressWarnings("unused")
        public void writeOSMPBF(StringTable stringtable, String ofn) throws IOException {
            op = new OSMPBF();
            op.writePBFHeaders(ofn, minx, miny, maxx, maxy);
            op.writePBFElements(stringtable, Boolean.TRUE, nodes, null, null);
            op.writePBFElements(stringtable, Boolean.TRUE, null, ways, null);
            op.writePBFElements(stringtable, Boolean.TRUE, null, null, relations);
        }



        private void clearNodeCache(int cell) {
            double[] ll = this.grid2xy(cell);

            int[] cellids = this.nodepos.keySet().toIntArray();

            for (int cellid : cellids) {
                if (!this.nodepos.containsKey(cellid)) {
                    continue;
                }

                double[] cell_ll = this.grid2xy(cellid);
                if (cell_ll[0] <= ll[0] - 24e3 && cell_ll[1] <= ll[1] - 24e3) {
                    int numdelids = this.nodepos.get(cellid).size();
                    this.nodepos.remove(cellid);
                    System.out.println("nodepos removed: " + cellid + ", " + numdelids + " ids by " + cell);
                }
            }

        }





    */

//    private void printCounts() {
//        System.out.println(nodes.size() + " nodes " + ways.size() + " ways " + relations.size() + " relations");
//    }
//
//    private void trackCounts() {
//        if (nodes.size() > max_nodes) max_nodes = nodes.size();
//        if (ways.size() > max_ways) max_ways = ways.size();
//        if (relations.size() > max_relations) max_relations = relations.size();
//
//        System.out.println("max_nodes " + max_nodes + ", max_ways " + max_ways + ", max_relations " + max_relations);
//
//    }
}
