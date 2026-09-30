package com.ansertech.service.inventory;

import com.ansertech.domain.entity.Product;
import com.ansertech.dto.response.ImportResultResponse;
import com.ansertech.dto.response.ImportResultResponse.Action;
import com.ansertech.exception.BusinessException;
import com.ansertech.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Carga masiva de inventario desde Excel (.xlsx), con el mismo formato de columnas que
 * genera GET /api/inventory/export — así el ciclo "exportar → editar → importar" funciona.
 *
 * Upsert por SKU: si el SKU no existe se crea el producto; si existe, se actualizan solo
 * las celdas que vienen con valor (una celda vacía deja el campo como estaba).
 * Las filas con error se omiten y se reportan; el resto se procesa.
 *
 * dryRun=true no guarda nada: las entidades existentes NO se modifican (los valores nuevos
 * se calculan aparte en PendingChange), porque cualquier cambio sobre una entidad
 * administrada se persistiría igual al hacer commit de la transacción.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryImportService {

    static final int MAX_ROWS = 5000;

    private final ProductRepository productRepository;

    /**
     * Columnas reconocidas. Es la única fuente de verdad del formato: la usan la importación
     * y la plantilla descargable (InventoryTemplateService).
     * label = encabezado tal como lo escribe /export y la plantilla;
     * headers = variantes aceptadas al importar (normalizadas: sin tildes, mayúsculas).
     */
    enum Col {
        SKU("SKU", "Siempre",
                "Código único del producto (p. ej. BAL-35422). Es la clave: si ya existe se actualiza, si no se crea.",
                "La fila da error", "SKU", "CODIGO"),
        NAME("NOMBRE", "Si el producto es nuevo",
                "Nombre del producto.", "Se mantiene el actual", "NOMBRE", "NOMBRE DEL PRODUCTO"),
        CATEGORY("CATEGORÍA", "No", "Categoría, p. ej. Balanzas.", "Se mantiene (nuevo: vacía)", "CATEGORIA"),
        DESCRIPTION("DESCRIPCIÓN", "No",
                "Descripción detallada: marca, modelo, especificaciones, sinónimos y usos. La IA (Gemini) la usa para encontrar el producto en los pedidos.",
                "Se mantiene (nuevo: vacía)", "DESCRIPCION"),
        UNIT("UNIDAD", "No", "unidad, caja, kit, metro…", "Se mantiene (nuevo: vacía)", "UNIDAD"),
        STOCK("STOCK", "No", "Cantidad disponible. Número ≥ 0.", "Se mantiene (nuevo: 0)", "STOCK"),
        MIN_STOCK("STOCK MÍNIMO", "No", "Umbral de alerta de stock bajo. Número ≥ 0.", "Se mantiene (nuevo: 5)", "STOCK MINIMO"),
        PRICE("PRECIO UNIT.", "Si el producto es nuevo",
                "Precio unitario SIN IGV (el sistema suma 18% al cotizar). Número ≥ 0 en soles enteros: si trae decimales se redondea (185,50 → 186).",
                "Se mantiene el actual", "PRECIO UNIT", "PRECIO UNITARIO", "PRECIO"),
        CURRENCY("MONEDA", "No", "PEN.", "Se mantiene (nuevo: PEN)", "MONEDA"),
        SUPPLIER("PROVEEDOR", "No", "Nombre del proveedor.", "Se mantiene (nuevo: vacío)", "PROVEEDOR"),
        BRAND("MARCA", "No", "Marca, p. ej. Hanna.", "Se mantiene (nuevo: vacía)", "MARCA"),
        ACTIVE("ACTIVO", "No", "Sí o No. Un producto inactivo no aparece en el inventario ni en las cotizaciones.",
                "Se mantiene (nuevo: Sí)", "ACTIVO");

        final String label;
        final String required;
        final String description;
        final String ifBlank;
        final List<String> headers;

        Col(String label, String required, String description, String ifBlank, String... headers) {
            this.label = label;
            this.required = required;
            this.description = description;
            this.ifBlank = ifBlank;
            this.headers = List.of(headers);
        }
    }

    @Transactional
    public ImportResultResponse importProducts(InputStream in, boolean dryRun) {
        try (Workbook wb = openWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.US);
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();

            Map<Col, Integer> cols = mapHeader(sheet.getRow(sheet.getFirstRowNum()), formatter);
            if (sheet.getLastRowNum() - sheet.getFirstRowNum() > MAX_ROWS) {
                throw new BusinessException("El archivo supera el máximo de " + MAX_ROWS + " filas");
            }

            Map<String, Product> existing = productRepository.findAll().stream()
                    .collect(Collectors.toMap(p -> p.getSku().toUpperCase(), Function.identity(), (a, b) -> a));
            Map<String, Integer> seenInFile = new HashMap<>();
            List<ImportResultResponse.Row> rows = new ArrayList<>();
            List<PendingChange> changes = new ArrayList<>();

            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                RowReader r = new RowReader(row, cols, formatter, evaluator);
                if (r.isBlank()) continue;

                int excelRow = i + 1;
                PendingChange change = analyzeRow(r, excelRow, existing, seenInFile);
                rows.add(change.toRow());
                if (change.action == Action.CREATE || change.action == Action.UPDATE) {
                    changes.add(change);
                }
            }

            if (!dryRun) {
                List<Product> toSave = changes.stream().map(PendingChange::apply).toList();
                productRepository.saveAll(toSave);
                log.info("Carga masiva de inventario: {} creados, {} actualizados",
                        count(rows, Action.CREATE), count(rows, Action.UPDATE));
            }

            return ImportResultResponse.builder()
                    .dryRun(dryRun)
                    .totalRows(rows.size())
                    .created(count(rows, Action.CREATE))
                    .updated(count(rows, Action.UPDATE))
                    .unchanged(count(rows, Action.UNCHANGED))
                    .errors(count(rows, Action.ERROR))
                    .rows(rows)
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── Análisis de una fila ─────────────────────────────────────────────────

    private PendingChange analyzeRow(RowReader r, int excelRow,
                                     Map<String, Product> existing, Map<String, Integer> seenInFile) {
        List<String> errors = new ArrayList<>();

        String sku = r.text(Col.SKU);
        String name = r.text(Col.NAME);
        String category = r.text(Col.CATEGORY);
        String description = r.text(Col.DESCRIPTION);
        String unit = r.text(Col.UNIT);
        String currency = r.text(Col.CURRENCY);
        String supplier = r.text(Col.SUPPLIER);
        String brand = r.text(Col.BRAND);
        BigDecimal stock = r.number(Col.STOCK, 3, errors);
        BigDecimal minStock = r.number(Col.MIN_STOCK, 3, errors);
        BigDecimal price = r.number(Col.PRICE, 0, errors);  // soles enteros, igual que Product.unitPrice
        Boolean active = r.bool(Col.ACTIVE, errors);

        PendingChange c = new PendingChange();
        c.rowNumber = excelRow;
        c.sku = sku;
        c.name = name;
        c.brand = brand;

        if (sku == null) {
            errors.add("SKU vacío");
        } else {
            Integer firstRow = seenInFile.putIfAbsent(sku.toUpperCase(), excelRow);
            if (firstRow != null) errors.add("SKU repetido en el archivo (ya aparece en la fila " + firstRow + ")");
        }
        checkLength(errors, "SKU", sku, 100);
        checkLength(errors, "NOMBRE", name, 500);
        checkLength(errors, "UNIDAD", unit, 50);
        checkLength(errors, "MONEDA", currency, 10);
        checkLength(errors, "CATEGORÍA", category, 255);
        checkLength(errors, "PROVEEDOR", supplier, 255);
        checkLength(errors, "MARCA", brand, 255);

        Product current = sku != null ? existing.get(sku.toUpperCase()) : null;
        if (current == null && name == null) errors.add("NOMBRE es obligatorio para un producto nuevo");
        if (current == null && price == null && !errors.stream().anyMatch(e -> e.startsWith("PRECIO"))) {
            errors.add("PRECIO es obligatorio para un producto nuevo");
        }

        if (!errors.isEmpty()) {
            c.action = Action.ERROR;
            c.message = String.join("; ", errors);
            if (current != null) { c.oldStock = current.getStockQuantity(); c.oldPrice = current.getUnitPrice(); }
            return c;
        }

        if (current == null) {
            c.action = Action.CREATE;
            c.target = Product.builder()
                    .sku(sku).name(name).category(category).description(description).unit(unit)
                    .stockQuantity(stock != null ? stock : BigDecimal.ZERO)
                    .minStockThreshold(minStock != null ? minStock : BigDecimal.valueOf(5))
                    .unitPrice(price)
                    .currency(currency != null ? currency.toUpperCase() : "PEN")
                    .supplierName(supplier).brand(brand)
                    .active(active != null ? active : Boolean.TRUE)
                    .build();
            c.newStock = c.target.getStockQuantity();
            c.newPrice = price;
            return c;
        }

        // Producto existente: solo cuentan las celdas con valor que difieren del valor actual.
        c.target = current;
        c.oldStock = current.getStockQuantity();
        c.oldPrice = current.getUnitPrice();
        c.newStock = stock != null ? stock : current.getStockQuantity();
        c.newPrice = price != null ? price : current.getUnitPrice();
        if (c.name == null) c.name = current.getName();
        if (c.brand == null) c.brand = current.getBrand();

        c.diff("name", name, current.getName());
        c.diff("category", category, current.getCategory());
        c.diff("description", description, current.getDescription());
        c.diff("unit", unit, current.getUnit());
        c.diff("currency", currency != null ? currency.toUpperCase() : null, current.getCurrency());
        c.diff("supplierName", supplier, current.getSupplierName());
        c.diff("brand", brand, current.getBrand());
        c.diff("stockQuantity", stock, current.getStockQuantity());
        c.diff("minStockThreshold", minStock, current.getMinStockThreshold());
        c.diff("unitPrice", price, current.getUnitPrice());
        c.diff("active", active, current.getActive());

        c.action = c.updates.isEmpty() ? Action.UNCHANGED : Action.UPDATE;
        return c;
    }

    private static void checkLength(List<String> errors, String col, String value, int max) {
        if (value != null && value.length() > max) errors.add(col + " supera " + max + " caracteres");
    }

    private static int count(List<ImportResultResponse.Row> rows, Action action) {
        return (int) rows.stream().filter(r -> r.getAction() == action).count();
    }

    // ── Cambio pendiente (no toca la entidad hasta apply) ────────────────────

    private static class PendingChange {
        int rowNumber;
        String sku, name, brand, message;
        Action action;
        BigDecimal oldStock, newStock, oldPrice, newPrice;
        Product target;
        final Map<String, Object> updates = new LinkedHashMap<>();

        void diff(String field, Object newValue, Object currentValue) {
            if (newValue == null) return;
            boolean same = (newValue instanceof BigDecimal n && currentValue instanceof BigDecimal cur)
                    ? n.compareTo(cur) == 0
                    : newValue.equals(currentValue);
            if (!same) updates.put(field, newValue);
        }

        Product apply() {
            if (action == Action.CREATE) return target;
            updates.forEach((field, v) -> {
                switch (field) {
                    case "name" -> target.setName((String) v);
                    case "category" -> target.setCategory((String) v);
                    case "description" -> target.setDescription((String) v);
                    case "unit" -> target.setUnit((String) v);
                    case "currency" -> target.setCurrency((String) v);
                    case "supplierName" -> target.setSupplierName((String) v);
                    case "brand" -> target.setBrand((String) v);
                    case "stockQuantity" -> target.setStockQuantity((BigDecimal) v);
                    case "minStockThreshold" -> target.setMinStockThreshold((BigDecimal) v);
                    case "unitPrice" -> target.setUnitPrice((BigDecimal) v);
                    case "active" -> target.setActive((Boolean) v);
                    default -> throw new IllegalStateException("Campo no soportado: " + field);
                }
            });
            return target;
        }

        ImportResultResponse.Row toRow() {
            return ImportResultResponse.Row.builder()
                    .rowNumber(rowNumber).sku(sku).name(name).brand(brand).action(action)
                    .oldStock(oldStock).newStock(newStock).oldPrice(oldPrice).newPrice(newPrice)
                    .message(message)
                    .build();
        }
    }

    // ── Lectura del Excel ────────────────────────────────────────────────────

    private static Workbook openWorkbook(InputStream in) {
        try {
            return WorkbookFactory.create(in);
        } catch (Exception e) {
            throw new BusinessException("No se pudo leer el archivo. Debe ser un Excel .xlsx válido.");
        }
    }

    private static Map<Col, Integer> mapHeader(Row header, DataFormatter formatter) {
        if (header == null) throw new BusinessException("El archivo está vacío");
        Map<Col, Integer> cols = new EnumMap<>(Col.class);
        for (Cell cell : header) {
            String h = normalize(formatter.formatCellValue(cell));
            for (Col col : Col.values()) {
                if (col.headers.contains(h)) cols.putIfAbsent(col, cell.getColumnIndex());
            }
        }
        for (Col required : List.of(Col.SKU, Col.NAME)) {
            if (!cols.containsKey(required)) {
                throw new BusinessException("Falta la columna obligatoria " + required.label
                        + ". Usa el formato de 'Exportar inventario'.");
            }
        }
        return cols;
    }

    /** Sin tildes, mayúsculas, sin puntuación, espacios simples: "PRECIO UNIT." → "PRECIO UNIT". */
    static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toUpperCase().replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * Acepta "1234.5", "1234,5", "1.234,50", "1,234.50" y "S/ 1 234,50".
     * Devuelve null si el texto está vacío.
     */
    static BigDecimal parseNumber(String raw) {
        if (raw == null) return null;
        String s = raw.replace("S/", "").replace("PEN", "").replaceAll("\\s", "");
        if (s.isEmpty()) return null;
        int lastComma = s.lastIndexOf(','), lastDot = s.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            s = lastComma > lastDot ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
        } else if (lastComma >= 0) {
            s = s.matches("-?\\d{1,3}(,\\d{3})+") ? s.replace(",", "") : s.replace(',', '.');
        }
        return new BigDecimal(s);
    }

    private static class RowReader {
        private final Row row;
        private final Map<Col, Integer> cols;
        private final DataFormatter formatter;
        private final FormulaEvaluator evaluator;

        RowReader(Row row, Map<Col, Integer> cols, DataFormatter formatter, FormulaEvaluator evaluator) {
            this.row = row;
            this.cols = cols;
            this.formatter = formatter;
            this.evaluator = evaluator;
        }

        boolean isBlank() {
            if (row == null) return true;
            return cols.keySet().stream().allMatch(c -> text(c) == null);
        }

        private Cell cell(Col col) {
            Integer idx = cols.get(col);
            return (row == null || idx == null) ? null : row.getCell(idx);
        }

        String text(Col col) {
            Cell c = cell(col);
            if (c == null) return null;
            String v;
            try {
                v = formatter.formatCellValue(c, evaluator);
            } catch (RuntimeException e) {   // fórmula rota: se usa el texto sin evaluar
                v = formatter.formatCellValue(c);
            }
            v = v.replace(' ', ' ').trim();
            return v.isEmpty() ? null : v;
        }

        BigDecimal number(Col col, int scale, List<String> errors) {
            Cell c = cell(col);
            if (c == null) return null;
            BigDecimal value;
            try {
                CellType type = c.getCellType() == CellType.FORMULA ? evaluator.evaluateFormulaCell(c) : c.getCellType();
                if (type == CellType.NUMERIC) {
                    value = BigDecimal.valueOf(c.getNumericCellValue());
                } else {
                    value = parseNumber(text(col));
                    if (value == null) return null;
                }
            } catch (Exception e) {
                errors.add(label(col) + " no es un número válido: '" + text(col) + "'");
                return null;
            }
            if (value.signum() < 0) {
                errors.add(label(col) + " no puede ser negativo");
                return null;
            }
            return value.setScale(scale, RoundingMode.HALF_UP);
        }

        Boolean bool(Col col, List<String> errors) {
            String v = text(col);
            if (v == null) return null;
            String n = normalize(v);
            if (Set.of("SI", "S", "TRUE", "1", "X", "ACTIVO").contains(n)) return true;
            if (Set.of("NO", "N", "FALSE", "0", "INACTIVO").contains(n)) return false;
            errors.add("ACTIVO debe ser Sí o No: '" + v + "'");
            return null;
        }

        private static String label(Col col) {
            return switch (col) {
                case STOCK -> "STOCK";
                case MIN_STOCK -> "STOCK MÍNIMO";
                case PRICE -> "PRECIO";
                default -> col.name();
            };
        }
    }
}
