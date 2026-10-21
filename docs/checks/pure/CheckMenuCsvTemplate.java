import com.kashmirmenu.app.service.MenuCsv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The sample file the frontend offers must be accepted, in full, by the server's own parser. */
public class CheckMenuCsvTemplate {
    public static void main(String[] args) throws Exception {
        Path ts = Path.of(args.length > 0 ? args[0] : "frontend/src/lib/menuCsvTemplate.ts");
        String src = Files.readString(ts);
        Matcher m = Pattern.compile("MENU_CSV_TEMPLATE\\s*=\\s*`(.*?)`;", Pattern.DOTALL).matcher(src);
        if (!m.find()) throw new AssertionError("template not found in " + ts);
        MenuCsv.Result r = MenuCsv.parse(m.group(1));
        if (!r.ok()) throw new AssertionError("the template the owner downloads is refused by the importer: " + r.problems());
        if (r.rows().size() < 3) throw new AssertionError("template should show several dishes");
        boolean veg = r.rows().stream().anyMatch(MenuCsv.Row::veg), nonVeg = r.rows().stream().anyMatch(x -> !x.veg());
        if (!(veg && nonVeg)) throw new AssertionError("template should show both veg and non-veg");
        System.out.println("MENU CSV TEMPLATE: parsed " + r.rows().size() + " rows with no problems");
    }
}
