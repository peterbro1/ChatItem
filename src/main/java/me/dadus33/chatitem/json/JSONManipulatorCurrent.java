package me.dadus33.chatitem.json;


import com.github.steveice10.opennbt.tag.builtin.*;
import com.google.gson.*;
import me.dadus33.chatitem.ChatItem;
import me.dadus33.chatitem.utils.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class JSONManipulatorCurrent implements JSONManipulator{

    private static final Class CRAFT_ITEM_STACK_CLASS = Reflect.getOBCClass("inventory.CraftItemStack");
    private static final Class NBT_STRING = Reflect.getNMSClass("NBTTagString");
    private static final Class NBT_LIST = Reflect.getNMSClass("NBTTagList");
    private static final Map<Type, Tag> TYPES_TO_OPEN_NBT_TAGS = new HashMap<>();
    private static final List<Class> NBT_BASE_CLASSES = new ArrayList<>();
    private static final List<Field> NBT_BASE_DATA_FIELD = new ArrayList<>();
    private static final Class NMS_ITEM_STACK_CLASS = Reflect.getNMSClass("ItemStack");
    private static final Method AS_NMS_COPY = Reflect.getMethod(CRAFT_ITEM_STACK_CLASS, "asNMSCopy", ItemStack.class);
    private static final Class NBT_TAG_COMPOUND = Reflect.getNMSClass("NBTTagCompound");
    private static final Method SAVE_NMS_ITEM_STACK_METHOD = Reflect.getMethod(NMS_ITEM_STACK_CLASS, "save", NBT_TAG_COMPOUND);
    private static final Field MAP = Reflect.getField(NBT_TAG_COMPOUND, "map");
    private static final Field LIST_FIELD = Reflect.getField(NBT_LIST, "list");

    //Tags to be ignored. Currently it only contains tags from PortableHorses, but feel free to submit a pull request to add tags from your plugins
    private static final List<String> IGNORED = Arrays.asList("horsetag", "phorse", "iscnameviz", "cname");

    private static final ConcurrentHashMap<Map.Entry<ProtocolVersion, ItemStack>, JsonObject> STACKS = new ConcurrentHashMap<>();

    static{
        NBT_BASE_CLASSES.add(Reflect.getNMSClass("NBTTagByte"));
        NBT_BASE_CLASSES.add(Reflect.getNMSClass("NBTTagByteArray"));
        NBT_BASE_CLASSES.add(Reflect.getNMSClass("NBTTagDouble"));
        NBT_BASE_CLASSES.add(Reflect.getNMSClass("NBTTagFloat"));
        NBT_BASE_CLASSES.add(Reflect.getNMSClass("NBTTagInt"));
        NBT_BASE_CLASSES.add(Reflect.getNMSClass("NBTTagIntArray"));
        NBT_BASE_CLASSES.add(Reflect.getNMSClass("NBTTagLong"));
        NBT_BASE_CLASSES.add(Reflect.getNMSClass("NBTTagShort"));

        for (Class NBT_BASE_CLASS : NBT_BASE_CLASSES) {
            NBT_BASE_DATA_FIELD.add(Reflect.getField(NBT_BASE_CLASS, "data"));
        }

        TYPES_TO_OPEN_NBT_TAGS.put(Byte.class, new ByteTag(""));
        TYPES_TO_OPEN_NBT_TAGS.put(Byte[].class, new ByteArrayTag(""));
        TYPES_TO_OPEN_NBT_TAGS.put(Double.class, new DoubleTag(""));
        TYPES_TO_OPEN_NBT_TAGS.put(Float.class, new FloatTag(""));
        TYPES_TO_OPEN_NBT_TAGS.put(Integer.class, new IntTag(""));
        TYPES_TO_OPEN_NBT_TAGS.put(Integer[].class, new IntArrayTag(""));
        TYPES_TO_OPEN_NBT_TAGS.put(Long.class, new LongTag(""));
        TYPES_TO_OPEN_NBT_TAGS.put(Short.class, new ShortTag(""));
        //Add the primitive types too, just in case
        TYPES_TO_OPEN_NBT_TAGS.put(byte.class, new ByteTag(""));
        TYPES_TO_OPEN_NBT_TAGS.put(byte[].class, new ByteArrayTag(""));
        TYPES_TO_OPEN_NBT_TAGS.put(double.class, new DoubleTag(""));
        TYPES_TO_OPEN_NBT_TAGS.put(float.class, new FloatTag(""));
        TYPES_TO_OPEN_NBT_TAGS.put(int.class, new IntTag(""));
        TYPES_TO_OPEN_NBT_TAGS.put(int[].class, new IntArrayTag(""));
        TYPES_TO_OPEN_NBT_TAGS.put(long.class, new LongTag(""));
        TYPES_TO_OPEN_NBT_TAGS.put(short.class, new ShortTag(""));

    }

    private List<String> replaces;
    private String rgx;
    private ProtocolVersion protocolVersion;
    private JsonObject itemTooltip;
    private JsonArray classicTooltip;
    private final JsonParser PARSER = new JsonParser();


    public String parse(String json, List<String> replacements, ItemStack item, String replacement, int protocol) throws InvocationTargetException, IllegalAccessException, InstantiationException, NoSuchFieldException, NoSuchMethodException {
        JsonObject obj = PARSER.parse(json).getAsJsonObject();
        Bukkit.broadcastMessage(json);

        JsonArray array = obj.getAsJsonArray("extra");
        replaces = replacements;
        String regex = "";
        for (int i = 0; i < replacements.size(); ++i) {
            if (replacements.size() == 1) {
                regex = Pattern.quote(replacements.get(0));
                break;
            }
            if (i == 0 || i + 1 == replacements.size()) {
                if (i == 0) {
                    regex = "(" + Pattern.quote(replacements.get(i));
                } else {
                    regex = regex.concat("|").concat(Pattern.quote(replacements.get(i))).concat(")");
                }
                continue;
            }
            regex = regex.concat("|").concat(Pattern.quote(replacements.get(i)));
        }
        rgx = regex;
        JsonArray rep = new JsonArray();
        final AbstractMap.SimpleEntry<ProtocolVersion, ItemStack> p = new AbstractMap.SimpleEntry<>(protocolVersion = ProtocolVersion.getVersion(protocol), item);

        if ((itemTooltip = STACKS.get(p)) == null) {
            JsonArray use = Translator.toJson(replacement); //We get the json representation of the old color formatting method

            JsonObject hover = PARSER.parse("{\"action\":\"show_text\", \"value\": \"\"}").getAsJsonObject(); //There's no public clone method for JSONObjects so we need to parse them every time

            String jsonRep = stringifyItem(item); //Get the JSON representation of the item (well, not really JSON, but rather a string representation of NBT data)
            hover.addProperty("value", jsonRep);

            JsonObject wrapper = new JsonObject(); //Create a wrapper object for the whole array
            wrapper.addProperty("text", ""); //The text field is compulsory, even if it's empty
            wrapper.add("extra", use);
            wrapper.add("hoverEvent", hover);

            itemTooltip = wrapper; //Save the tooltip for later use when we encounter a placeholder
            STACKS.put(p, itemTooltip); //Save it in the cache too so when parsing other packets with the same item (and client version) we no longer have to create it again
            Bukkit.getScheduler().runTaskLaterAsynchronously(ChatItem.getInstance(), () ->
                    STACKS.remove(p)
            , 100L); //We remove it later when no longer needed to save memory
        }

        for (int i = 0; i < array.size(); ++i) {
            if (array.get(i).isJsonObject()){
                JsonObject o = array.get(i).getAsJsonObject();
                boolean inside = false;
                for (String replace : replacements)
                    if (o.toString().contains(replace)) {
                        if (inside) {
                            break;
                        }
                        inside = true;
                    }
                JsonElement text = o.get("text");
                if (text == null) {
                    JsonElement el = o.get("extra");
                    if (el != null) {
                        JsonArray jar = el.getAsJsonArray();
                        if(jar.size()!=0) {
                            jar = parseArray(jar);
                            o.add("extra", jar);
                        }else{
                            o.remove("extra");
                        }
                    }
                    continue;
                } else {
                    if (text.getAsString().isEmpty()) {
                        JsonElement el = o.get("extra");
                        if (el != null) {
                            JsonArray jar = el.getAsJsonArray();
                            if(jar.size()!=0) {
                                jar = parseArray(jar);
                                o.add("extra", jar);
                            }else{
                                o.remove("extra");
                            }
                        }
                    }
                }

                String msg = text.getAsString();
                boolean isLast = false;
                boolean done = false;
                boolean fnd;
                String[] splits;
                for (String repls : replacements) {
                    if (done) {
                        break;
                    }
                    isLast = msg.endsWith(repls);
                    if (isLast) {
                        done = true;
                        msg = msg.concat(".");
                    }
                }
                splits = msg.split(regex);
                fnd = splits.length != 1;
                if (fnd)
                    for (int j = 0; j < splits.length; ++j) {
                        boolean endDot = (j == splits.length - 1) && isLast;
                        if (!splits[j].isEmpty() && !endDot) {
                            String st = o.toString();
                            JsonObject fix = PARSER.parse(st).getAsJsonObject();
                            fix.addProperty("text", splits[j]);
                            rep.add(fix);
                        }
                        if (j != splits.length - 1) {
                            rep.add(itemTooltip);
                        }
                    }
                if (!fnd) {
                    rep.add(o);
                }
            }else{
                if(array.get(i).isJsonNull()){
                    continue;
                }else{
                    if(array.get(i).isJsonArray()){
                        JsonArray jar = array.get(i).getAsJsonArray();
                        if(jar.size()!=0) {
                            jar = parseArray(array.get(i).getAsJsonArray());
                            rep.set(i, jar);
                        }
                    }else{


                        String msg = array.get(i).getAsString();
                        boolean isLast = false;
                        boolean done = false;
                        boolean fnd;
                        String[] splits;
                        for (String repls : replacements) {
                            if (done) {
                                break;
                            }
                            isLast = msg.endsWith(repls);
                            if (isLast) {
                                done = true;
                                msg = msg.concat(".");
                            }
                        }
                        splits = msg.split(regex);
                        fnd = splits.length != 1;
                        if (fnd)
                            for (int j = 0; j < splits.length; ++j) {
                                boolean endDot = (j == splits.length - 1) && isLast;
                                if (!splits[j].isEmpty() && !endDot) {
                                    JsonElement fix = new JsonPrimitive(splits[j]);
                                    rep.add(fix);
                                }
                                if (j != splits.length - 1) {
                                    rep.add(itemTooltip);
                                }
                            }
                        if (!fnd) {
                            rep.add(array.get(i));
                        }
                    }
                }
            }

        }
        obj.add("extra", rep);
        if(!obj.has("text")){
            obj.addProperty("text", "");
        }
        return obj.toString();
    }

    @Override
    public String parseEmpty(String json, List<String> replacements, String repl, List<String> tooltip, Player sender) {
        JsonObject obj = PARSER.parse(json).getAsJsonObject();
        JsonArray array = obj.getAsJsonArray("extra");
        replaces = replacements;
        String regex = "";
        for (int i = 0; i < replacements.size(); ++i) {
            if (replacements.size() == 1) {
                regex = Pattern.quote(replacements.get(0));
                break;
            }
            if (i == 0 || i + 1 == replacements.size()) {
                if (i == 0) {
                    regex = "(" + Pattern.quote(replacements.get(i));
                } else {
                    regex = regex.concat("|").concat(Pattern.quote(replacements.get(i))).concat(")");
                }
                continue;
            }
            regex = regex.concat("|").concat(Pattern.quote(replacements.get(i)));
        }
        rgx = regex;
        JsonArray rep = new JsonArray();
        JsonArray use = Translator.toJson(repl.replace("{name}", sender.getName()).replace("{display-name}", sender.getDisplayName()));
        JsonObject hover = PARSER.parse("{\"action\":\"show_text\", \"value\": \"\"}").getAsJsonObject();

        StringBuilder oneLineTooltip = new StringBuilder("");
        int index = 0;
        for(String m : tooltip){
           oneLineTooltip.append(m.replace("{name}", sender.getName()).replace("{display-name}", sender.getDisplayName()));
           ++index;
           if(index!=tooltip.size()){
               oneLineTooltip.append('\n');
           }
        }

        hover.add("value", new JsonPrimitive(oneLineTooltip.toString()));
        for (JsonElement ob : use)
            ob.getAsJsonObject().add("hoverEvent", hover);

        classicTooltip = use;

        for (int i = 0; i < array.size(); ++i) {
            if (array.get(i).isJsonObject()){
                JsonObject o = array.get(i).getAsJsonObject();
                boolean inside = false;
                for (String replace : replacements)
                    if (o.toString().contains(replace)) {
                        if (inside) {
                            break;
                        }
                        inside = true;
                    }
                JsonElement text = o.get("text");
                if (text == null) {
                    JsonElement el = o.get("extra");
                    if (el != null) {
                        JsonArray jar = el.getAsJsonArray();
                        if(jar.size()!=0) {
                            jar = parseNoItemArray(jar);
                            o.add("extra", jar);
                        }else{
                            o.remove("extra");
                        }
                    }
                    continue;
                } else {
                    if (text.getAsString().isEmpty()) {
                        JsonElement el = o.get("extra");
                        if (el != null) {
                            JsonArray jar = el.getAsJsonArray();
                            if(jar.size()!=0) {
                                jar = parseNoItemArray(jar);
                                o.add("extra", jar);
                            }else{
                                o.remove("extra");
                            }
                        }
                    }
                }

                String msg = text.getAsString();
                boolean isLast = false;
                boolean done = false;
                boolean fnd;
                String[] splits;
                for (String repls : replacements) {
                    if (done) {
                        break;
                    }
                    isLast = msg.endsWith(repls);
                    if (isLast) {
                        done = true;
                        msg = msg.concat(".");
                    }
                }
                splits = msg.split(regex);
                fnd = splits.length != 1;
                if (fnd)
                    for (int j = 0; j < splits.length; ++j) {
                        boolean endDot = (j == splits.length - 1) && isLast;
                        if (!splits[j].isEmpty() && !endDot) {
                            String st = o.toString();
                            JsonObject fix = PARSER.parse(st).getAsJsonObject();
                            fix.addProperty("text", splits[j]);
                            rep.add(fix);
                        }
                        if (j != splits.length - 1) {
                            rep.addAll(use);
                        }
                    }
                if (!fnd) {
                    rep.add(o);
                }
            }else{
                if(array.get(i).isJsonNull()){
                    continue;
                }else{
                    if(array.get(i).isJsonArray()){
                        JsonArray jar = array.get(i).getAsJsonArray();
                        if(jar.size()!=0) {
                            jar = parseNoItemArray(array.get(i).getAsJsonArray());
                            rep.set(i, jar);
                        }
                    }else{


                        String msg = array.get(i).getAsString();
                        boolean isLast = false;
                        boolean done = false;
                        boolean fnd;
                        String[] splits;
                        for (String repls : replacements) {
                            if (done) {
                                break;
                            }
                            isLast = msg.endsWith(repls);
                            if (isLast) {
                                done = true;
                                msg = msg.concat(".");
                            }
                        }
                        splits = msg.split(regex);
                        fnd = splits.length != 1;
                        if (fnd)
                            for (int j = 0; j < splits.length; ++j) {
                                boolean endDot = (j == splits.length - 1) && isLast;
                                if (!splits[j].isEmpty() && !endDot) {
                                    JsonElement fix = new JsonPrimitive(splits[j]);
                                    rep.add(fix);
                                }
                                if (j != splits.length - 1) {
                                    rep.addAll(use);
                                }
                            }
                        if (!fnd) {
                            rep.add(array.get(i));
                        }


                    }
                }
            }

        }
        obj.add("extra", rep);
        return obj.toString();
    }


    private JsonArray parseNoItemArray(JsonArray arr) {
        JsonArray replacer = new JsonArray();
        for (int i = 0; i < arr.size(); ++i) {
            if (arr.get(i).isJsonObject()){
                    JsonObject o = arr.get(i).getAsJsonObject();
                boolean inside = false;
                for (String replacement : replaces)
                    if (o.toString().contains(replacement)) {
                        if (inside) {
                            break;
                        }
                        inside = true;
                    }
                if (!inside) { //the placeholder we're looking for is not inside this element, so we continue searching
                    replacer.add(o);
                    continue;
                }
                JsonElement text = o.get("text");
                if (text == null) {
                    continue;
                }
                if (text.getAsString().isEmpty()) {
                    JsonElement el = o.get("extra");
                    if (el == null) {
                        continue;
                    }
                    JsonArray jar = el.getAsJsonArray();
                    if(jar.size()!=0) {
                        jar = parseNoItemArray(jar);
                        o.add("extra", jar);
                    }else{
                        o.remove("extra");
                    }
                }

                String msg = text.getAsString();
                boolean isLast = false;
                boolean done = false;
                boolean fnd;
                String[] splits;
                for (String repls : replaces) {
                    if (done) {
                        break;
                    }
                    isLast = msg.endsWith(repls);
                    if (isLast) {
                        done = true;
                        msg = msg.concat(".");
                    }
                }
                splits = msg.split(rgx);
                fnd = splits.length != 1;
                if (fnd)
                    for (int j = 0; j < splits.length; ++j) {
                        boolean endDot = (j == splits.length - 1) && isLast;
                        if (!splits[j].isEmpty() && !endDot) {
                            String st = o.toString();
                            JsonObject fix = PARSER.parse(st).getAsJsonObject();
                            fix.addProperty("text", splits[j]);
                            replacer.add(fix);
                        }
                        if (j != splits.length - 1) {
                            replacer.addAll(classicTooltip);
                        }
                    }
                if (!fnd) {
                    replacer.add(o);
                }
            }else{
                if(arr.get(i).isJsonNull()){
                    continue;
                }else{
                    if(arr.get(i).isJsonArray()){
                        JsonArray jar = arr.get(i).getAsJsonArray();
                        if(jar.size()!=0) {
                            jar = parseNoItemArray(arr.get(i).getAsJsonArray());
                            replacer.set(i, jar);
                        }
                    }else{
                        String msg = arr.get(i).getAsString();
                        boolean isLast = false;
                        boolean done = false;
                        boolean fnd;
                        String[] splits;
                        for (String repls : replaces) {
                            if (done) {
                                break;
                            }
                            isLast = msg.endsWith(repls);
                            if (isLast) {
                                done = true;
                                msg = msg.concat(".");
                            }
                        }
                        splits = msg.split(rgx);
                        fnd = splits.length != 1;
                        if (fnd)
                            for (int j = 0; j < splits.length; ++j) {
                                boolean endDot = (j == splits.length - 1) && isLast;
                                if (!splits[j].isEmpty() && !endDot) {
                                    JsonElement fix = new JsonPrimitive(splits[j]);
                                    replacer.add(fix);
                                }
                                if (j != splits.length - 1) {
                                    replacer.addAll(classicTooltip);
                                }
                            }
                        if (!fnd) {
                            replacer.add(arr.get(i));
                        }
                    }
                }
            }

        }
        return replacer;
    }

    private JsonArray parseArray(JsonArray arr) {
        JsonArray replacer = new JsonArray();
        for (int i = 0; i < arr.size(); ++i) {
            if (arr.get(i).isJsonObject()){
                JsonObject o = arr.get(i).getAsJsonObject();
                boolean inside = false;
                for (String replacement : replaces)
                    if (o.toString().contains(replacement)) {
                        if (inside) {
                            break;
                        }
                        inside = true;
                    }
                if (!inside) { //the placeholder we're looking for is not inside this element, so we continue searching
                    replacer.add(o);
                    continue;
                }
                JsonElement text = o.get("text");
                if (text == null) {
                    continue;
                }
                if (text.getAsString().isEmpty()) {
                    JsonElement el = o.get("extra");
                    if (el == null) {
                        continue;
                    }
                    JsonArray jar = el.getAsJsonArray();
                    if(jar.size()!=0) {
                        jar = parseArray(jar);
                        o.add("extra", jar);
                    }else{
                        o.remove("extra");
                    }
                }

                String msg = text.getAsString();
                boolean isLast = false;
                boolean done = false;
                boolean fnd;
                String[] splits;
                for (String repls : replaces) {
                    if (done) {
                        break;
                    }
                    isLast = msg.endsWith(repls);
                    if (isLast) {
                        done = true;
                        msg = msg.concat(".");
                    }
                }
                splits = msg.split(rgx);
                fnd = splits.length != 1;
                if (fnd)
                    for (int j = 0; j < splits.length; ++j) {
                        boolean endDot = (j == splits.length - 1) && isLast;
                        if (!splits[j].isEmpty() && !endDot) {
                            String st = o.toString();
                            JsonObject fix = PARSER.parse(st).getAsJsonObject();
                            fix.addProperty("text", splits[j]);
                            replacer.add(fix);
                        }
                        if (j != splits.length - 1) {
                            replacer.add(itemTooltip);
                        }
                    }
                if (!fnd) {
                    replacer.add(o);
                }
            }else{
                if(arr.get(i).isJsonNull()){
                    continue;
                }else{
                    if(arr.get(i).isJsonArray()){
                        JsonArray jar = arr.get(i).getAsJsonArray();
                        if(jar.size()!=0) {
                            jar = parseArray(arr.get(i).getAsJsonArray());
                            replacer.set(i, jar);
                        }
                    }else{
                        String msg = arr.get(i).getAsString();
                        boolean isLast = false;
                        boolean done = false;
                        boolean fnd;
                        String[] splits;
                        for (String repls : replaces) {
                            if (done) {
                                break;
                            }
                            isLast = msg.endsWith(repls);
                            if (isLast) {
                                done = true;
                                msg = msg.concat(".");
                            }
                        }
                        splits = msg.split(rgx);
                        fnd = splits.length != 1;
                        if (fnd)
                            for (int j = 0; j < splits.length; ++j) {
                                boolean endDot = (j == splits.length - 1) && isLast;
                                if (!splits[j].isEmpty() && !endDot) {
                                    JsonElement fix = new JsonPrimitive(splits[j]);
                                    replacer.add(fix);
                                }
                                if (j != splits.length - 1) {
                                    replacer.add(itemTooltip);
                                }
                            }
                        if (!fnd) {
                            replacer.add(arr.get(i));
                        }
                    }
                }
            }

        }
        return replacer;
    }


    private String escapeSpecials(String initial){
        return initial.replace("\"", "\\\"").replace("\\", "\\\\").replace("\b", "\\b")
                .replace("\f", "\\f").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }


    private Item toItem(ItemStack is) throws IllegalAccessException, InstantiationException, InvocationTargetException, NoSuchMethodException {
        CompoundTag tag = new CompoundTag("tag");

        Object nmsStack = AS_NMS_COPY.invoke(null, is);
        Object nmsTag = NBT_TAG_COMPOUND.newInstance();
        SAVE_NMS_ITEM_STACK_METHOD.invoke(nmsStack, nmsTag);
        Map<String, Object> nmsMap = (Map<String, Object>) MAP.get(nmsTag);
        String id = nmsMap.get("id").toString().replace("\"", "");
        Object realTag = nmsMap.get("tag");
        if(NBT_TAG_COMPOUND.isInstance(realTag)) {  //We need to make sure this is indeed an NBTTagCompound
            Map<String, Object> realMap = (Map<String, Object>) MAP.get(realTag);
            Set<Map.Entry<String, Object>> entrySet = realMap.entrySet();
            Map<String, Tag> map = tag.getValue();
            for (Map.Entry<String, Object> entry : entrySet) {
                map.put(entry.getKey(), toOpenTag(entry.getValue(), entry.getKey()));
            }
            tag.setValue(map);
        }

        Item item = new Item();
        item.setAmount((byte)is.getAmount());
        item.setData(is.getDurability());
        item.setId(id);
        item.setTag(tag);
        return item;
    }

    private Tag toOpenTag(Object nmsTag, String name) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException, InstantiationException {
        if(NBT_TAG_COMPOUND.isInstance(nmsTag)) {
            CompoundTag tag = new CompoundTag(name);
            Map<String, Tag> tagMap = tag.getValue();

            Map<String, Object> nmsMap = (Map<String, Object>) MAP.get(nmsTag);
            Set<Map.Entry<String, Object>> entrySet = nmsMap.entrySet();
            for (Map.Entry<String, Object> entry : entrySet) {
                Tag value = toOpenTag(entry.getValue(), entry.getKey());
                tagMap.put(entry.getKey(), value);
            }
            tag.setValue(tagMap);
            return tag;
        } else {
            //Strings are a special case as they need proper escaping
            if(NBT_STRING.isInstance(nmsTag)) {
                String toString = nmsTag.toString();
                if (toString.startsWith("\"") && toString.endsWith("\"")) {
                    toString = toString.substring(1, toString.length() - 1);
                    //toString = escapeSpecials(toString);
                    return new StringTag(name, toString);
                }
            }

            //NBTTag Lists are also special, as they are a sort of compound themselves and need to be parsed recursively
            if(NBT_LIST.isInstance(nmsTag)){
                List<Object> nmsNBTBaseList = (List<Object>) LIST_FIELD.get(nmsTag);
                List<Tag> list = new ArrayList<>();
                int i = 0;
                for(Object baseTag : nmsNBTBaseList){
                    list.add(toOpenTag(baseTag, String.valueOf(i)));
                    ++i;
                }
                return new ListTag(name, list);
            }

            for(int i = 0; i < NBT_BASE_CLASSES.size(); ++i){
                Class c = NBT_BASE_CLASSES.get(i);
                if(c.isInstance(nmsTag)){
                    Object value = NBT_BASE_DATA_FIELD.get(i).get(nmsTag);

                    Tag t = TYPES_TO_OPEN_NBT_TAGS.get(value.getClass()).getClass().getConstructor(String.class).newInstance(name);

                    if(t instanceof ByteTag){
                        ((ByteTag)t).setValue((byte)value);
                        ((ByteTag)t).setValue((byte)value);
                        return t;
                    }
                    if(t instanceof ByteArrayTag){
                        ((ByteArrayTag)t).setValue((byte[])value);
                        return t;
                    }
                    if(t instanceof DoubleTag){
                        ((DoubleTag)t).setValue((double)value);
                        return t;
                    }
                    if(t instanceof FloatTag){
                        ((FloatTag)t).setValue((float)value);
                        return t;
                    }
                    if(t instanceof IntTag){
                        ((IntTag)t).setValue((int)value);
                        return t;
                    }
                    if(t instanceof IntArrayTag){
                        ((IntArrayTag)t).setValue((int[])value);
                        return t;
                    }
                    if(t instanceof LongTag){
                        ((LongTag)t).setValue((long)value);
                        return t;
                    }
                    if(t instanceof ShortTag){
                        ((ShortTag)t).setValue((short)value);
                        return t;
                    }
                    return null; //Should never happen
                }
            }
            return null; //Should never happen
        }
    }

    private String stringifyItem(ItemStack stack) throws InvocationTargetException, IllegalAccessException, InstantiationException, NoSuchFieldException, NoSuchMethodException {
       // return JsonUtil.toJson(stack);
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        String name = meta == null ? stack.getI18NDisplayName() : meta.getDisplayName();
        if (name == null){
        }
        if (stack.getType().equals((Object) Material.AIR)) {
            name = "Nothing";
        }
        final StringBuilder lore = new StringBuilder(name).append("\n");

        //enchantments
        final Map<Enchantment, Integer> enchants = (Map<Enchantment, Integer>)stack.getEnchantments();
        for (final Enchantment en : enchants.keySet()) {
            lore.append("§7" + getName(en) + " " + enchants.get(en)).append("\n");
        }
        //enchantments

        //lore
        if (meta != null && !meta.getLore().isEmpty()) {
                for (final String l : meta.getLore()) {
                    lore.append("§5").append(l).append("\n");
                }

        }
        //lore

        //item type
        String[] st = stack.getType().name().toLowerCase().split("_");
        StringBuilder stb = new StringBuilder();
        for (String s : st){
           stb.append(s.substring(0, 1).toUpperCase() + s.substring(1));
        }
        lore.append("§8" + stb.toString());
        //item type
        String finallore = lore.toString();
        if (finallore.endsWith("\n")) {
            finallore = finallore.substring(0, finallore.length() - 1);
        }

       return finallore;
    }

    private String stringifyTag(Tag normalTag){
        if(normalTag instanceof CompoundTag) {
            StringBuilder sb = new StringBuilder("{");
            CompoundTag tagCompound = (CompoundTag) normalTag;
            Map<String, Tag> tagMap = tagCompound.getValue();
            Set<Map.Entry<String, Tag>> entrySet = tagMap.entrySet();
            for(Map.Entry<String, Tag> entry : entrySet){
                String value = stringifyTag(entry.getValue());
                if(value == null){
                    continue;
                }
                if(sb.length()>1){
                    sb.append(",");
                }
                sb.append(entry.getKey()).append(":").append(value);
            }

            sb.append("}");
            return sb.toString();
        } else {
            if(normalTag instanceof StringTag) {
                return "\""+((StringTag)normalTag).getValue()+"\""; //Should be already escaped
            }

            if(normalTag instanceof ListTag){
                List<Tag> list = ((ListTag)normalTag).getValue();
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for(Tag tag : list){
                    String index = tag.getName();
                    String value = stringifyTag(tag);
                    if(value == null){
                        continue;
                    }
                    if(!first){
                        sb.append(",");
                    }
                    if(protocolVersion.MAX_VER <= ProtocolVersion.V1_11_X.MAX_VER){ //it's before 1.12
                        sb.append(index).append(":").append(value);
                    }else{
                        sb.append(value);
                    }

                    first = false;
                }
                sb.append("]");
                return sb.toString();
            }

            if(normalTag instanceof ByteTag){
                return normalTag.getValue()+"b";
            }
            if(normalTag instanceof ByteArrayTag){
                return "["+((byte[])normalTag.getValue()).length+" bytes]";
            }
            if(normalTag instanceof DoubleTag){
                return (double)normalTag.getValue()+"d";
            }
            if(normalTag instanceof FloatTag){
                return (float)normalTag.getValue()+"f";
            }
            if(normalTag instanceof IntTag){
                return String.valueOf((int)normalTag.getValue());
            }
            if(normalTag instanceof IntArrayTag){
                int[] array = (int[])normalTag.getValue();
                if(array.length == 0){
                    return null;
                }
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for(int i : array){
                    if(!first){
                        sb.append(",");
                    }
                    sb.append(i);
                    first = false;
                }
                sb.append("]");
                return sb.toString();
            }
            if(normalTag instanceof LongTag){
                return (long)normalTag.getValue()+"L";
            }
            if(normalTag instanceof ShortTag){
                return (short)normalTag.getValue()+"s";
            }
            return null; //Should never happen
        }
    }

    public String getName(final Enchantment ench) {
        final String name = ench.getName();
        final String s;
        switch (s = name) {
            case "OXYGEN": {
                return "Respiration";
            }
            case "DAMAGE_ARTHROPODS": {
                return "Bane Of Arthropods";
            }
            case "PROTECTION_PROJECTILE": {
                return "Projectile Protection";
            }
            case "DAMAGE_ALL": {
                return "Sharpness";
            }
            case "ARROW_FIRE": {
                return "Flame";
            }
            case "PROTECTION_FALL": {
                return "Feather Falling";
            }
            case "PROTECTION_FIRE": {
                return "Fire Protection";
            }
            case "KNOCKBACK": {
                return "Knockback";
            }
            case "FIRE_ASPECT": {
                return "Fire Aspect";
            }
            case "WATER_WORKER": {
                return "Aqua Affinity";
            }
            case "ARROW_DAMAGE": {
                return "Power";
            }
            case "ARROW_KNOCKBACK": {
                return "Punch";
            }
            case "PROTECTION_ENVIRONMENTAL": {
                return "Protection";
            }
            case "LOOT_BONUS_BLOCKS": {
                return "Fortune";
            }
            case "DURABILITY": {
                return "Unbreaking";
            }
            case "LOOT_BONUS_MOBS": {
                return "Looting";
            }
            case "DIG_SPEED": {
                return "Efficiency";
            }
            case "ARROW_INFINITE": {
                return "Infinity";
            }
            case "DAMAGE_UNDEAD": {
                return "Smite";
            }
            case "SILK_TOUCH": {
                return "Silk Touch";
            }
            case "PROTECTION_EXPLOSIONS": {
                return "Blast Protection";
            }
            case "MENDING": {
                return "Mending";            }
            default:
                break;
        }
        throw new RuntimeException("Unknown Enchantment");
    }


}
