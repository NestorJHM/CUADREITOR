// Scheduler.java (resumen ancho con control deslizante + reubicación del checkbox)
// -----------------------------------------------------------------------------
// Cambios clave:
// • El área de RESUMEN es un panel derecho en un JSplitPane horizontal (control
//   deslizante). Por defecto ocupa ~65% del ancho, ajustable por el usuario.
// • El checkbox “Bloquear teoría+prácticas al mismo subgrupo” se coloca DEBAJO
//   de los checks de elección de día libre.
// • Se mantienen: días libres múltiples, botón “Calcular horario”, tabla ancha,
//   y (opcional) exclusión de prácticas por asignatura si ya integraste “SP”.
// -----------------------------------------------------------------------------

import java.awt.*;
import java.io.File;
import java.text.Collator;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import javax.swing.table.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Scheduler {
    /* ---------- Records ---------- */
    public record Session(
            DayOfWeek day, LocalTime start, LocalTime end,
            String asignatura, String grupo, String tipo, String curso, String semestre) {
        boolean overlaps(Session o) {
            return day == o.day && start.isBefore(o.end) && o.start.isBefore(end);
        }
    }
    public record Group(String code, List<Session> sessions) {}
    public record Subject(String name, List<Group> groups) {}

    /* ---------- Campos de instancia ---------- */
    private final List<Subject> allSubjects;
    private JFrame frame;
    private JComboBox<String> semesterCombo;
    private List<JToggleButton> subjectButtons;
    private JTextArea outputArea;
    private JLabel comboCounterLabel;

    // Días libres múltiples y bloqueo de subgrupo
    private final Map<DayOfWeek, JCheckBox> freeDayChecks = new LinkedHashMap<>();
    private JCheckBox sameSubgroupBox;

    // (Opcional) Si usas “SP: sin prácticas” por asignatura, declara y usa este mapa:
    private final Map<String, JCheckBox> skipPracticesBySubject = new HashMap<>();

    private static final IntRef combosTested = new IntRef(0);

    /* ---------------------- main ---------------------- */
    public static void main(String[] args) {
        Font uiFont;
        try { uiFont = new Font("Consolas", Font.PLAIN, 14); }
        catch (Exception e) { uiFont = new Font(Font.MONOSPACED, Font.PLAIN, 14); }
        Font finalUiFont = uiFont;
        UIManager.getLookAndFeelDefaults().keySet().forEach(k -> {
            if (k.toString().toLowerCase().contains("font"))
                UIManager.put(k, new FontUIResource(finalUiFont));
        });
        Font finalFont = uiFont;
        SwingUtilities.invokeLater(() -> {
            try { new Scheduler(finalFont); }
            catch (Exception e) {
                System.err.println("Error al iniciar Cuadreitor: " + e.getMessage());
                e.printStackTrace(System.err);
                System.exit(1);
            }
        });
    }

    /* --------------------- ctor ---------------------- */
    public Scheduler(Font monoFont) throws Exception {
        File json = new File("horarios.json");
        if (!json.exists()) {
            JOptionPane.showMessageDialog(null,
                    "No se encontró horarios.json en:\n" + json.getAbsolutePath(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        List<Session> raw = loadEntries(json);
        allSubjects = buildSubjects(raw);
        initGui(monoFont);
    }

    /* ---------------------- GUI ---------------------- */
    private void initGui(final Font font) {
        frame = new JFrame("Cuadreitor v3.1");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        /* -------- Panel izquierdo: asignaturas + opciones -------- */
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setMinimumSize(new Dimension(260, 300));

        // Cabecera + opciones en vertical
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));

        JLabel selLbl = new JLabel("Selecciona asignaturas:");
        selLbl.setFont(font.deriveFont(Font.BOLD));
        selLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(selLbl);

        // Fila: DÍAS LIBRES (Lun–Vie)
        JPanel daysRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        daysRow.add(new JLabel("Días libres:"));
        crearCheckDia(daysRow, "Lun", DayOfWeek.MONDAY, font);
        crearCheckDia(daysRow, "Mar", DayOfWeek.TUESDAY, font);
        crearCheckDia(daysRow, "Mié", DayOfWeek.WEDNESDAY, font);
        crearCheckDia(daysRow, "Jue", DayOfWeek.THURSDAY, font);
        crearCheckDia(daysRow, "Vie", DayOfWeek.FRIDAY, font);
        daysRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(daysRow);

        // *** NUEVO: checkbox de bloqueo debajo de los checks de día libre ***
        sameSubgroupBox = new JCheckBox("Bloquear teoría+prácticas al mismo subgrupo");
        sameSubgroupBox.setFont(font);
        sameSubgroupBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        sameSubgroupBox.setToolTipText("Si se activa, se elige un único subgrupo por asignatura para todos los tipos.");
        north.add(sameSubgroupBox);

        // Fila: Semestre
        JPanel semRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        semRow.add(new JLabel("Semestre:"));
        semesterCombo = new JComboBox<>(new String[]{"1","2"});
        semesterCombo.setFont(font);
        semRow.add(semesterCombo);
        semRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(semRow);

        leftPanel.add(north, BorderLayout.NORTH);

        // Botonera de asignaturas (NO estirada) + (opcional) mini-check “SP”
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        subjectButtons = new ArrayList<>();

        // Ordenar por semestre → curso → nombre
        List<Subject> sorted = new ArrayList<>(allSubjects);
        Collator coll = Collator.getInstance(new Locale("es", "ES"));
        sorted.sort(Comparator
                .comparingInt((Subject s) -> Integer.parseInt(s.groups().get(0).sessions().get(0).semestre))
                .thenComparingInt(s -> Integer.parseInt(s.groups().get(0).sessions().get(0).curso))
                .thenComparing(Subject::name, coll));

        int lastSem = -1, lastCurso = -1;
        JPanel gridCursoPanel = null;
        int gridRow = 0, gridCol = 0;

        for (Subject subj : sorted) {
            int sem = Integer.parseInt(subj.groups().get(0).sessions().get(0).semestre);
            int cur = Integer.parseInt(subj.groups().get(0).sessions().get(0).curso);

            if (sem != lastSem || cur != lastCurso) {
                JLabel header = new JLabel("Semestre " + sem + " - Curso " + cur);
                header.setFont(font.deriveFont(Font.BOLD));
                header.setAlignmentX(Component.LEFT_ALIGNMENT);
                buttonPanel.add(header);

                gridCursoPanel = new JPanel(new GridBagLayout());
                gridCursoPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                gridCursoPanel.setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));
                buttonPanel.add(gridCursoPanel);

                lastSem = sem; lastCurso = cur;
                gridRow = 0; gridCol = 0;
            }

            String label = subj.name();
            JToggleButton btn = new JToggleButton(label);
            btn.setFont(font);
            btn.setActionCommand(subj.name());
            btn.setMargin(new Insets(2, 6, 2, 6));
            btn.setFocusPainted(false);

            // (Opcional) mini-check SP por asignatura
            JCheckBox cbSP = new JCheckBox("SP");
            cbSP.setFont(font.deriveFont(Math.max(10f, font.getSize()-3f)));
            cbSP.setToolTipText("Sin prácticas: excluir 'Prácticas' y 'Prácticas aula' de esta asignatura.");
            skipPracticesBySubject.put(subj.name(), cbSP);

            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            row.add(btn);
            row.add(cbSP);

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = gridCol;
            gbc.gridy = gridRow;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = new Insets(2, 2, 2, 8);
            gbc.fill = GridBagConstraints.NONE;
            Objects.requireNonNull(gridCursoPanel).add(row, gbc);

            subjectButtons.add(btn);

            gridCol++;
            if (gridCol == 2) { gridCol = 0; gridRow++; }
        }

        JScrollPane listScroll = new JScrollPane(buttonPanel);
        listScroll.setPreferredSize(new Dimension(300, 520));
        leftPanel.add(listScroll, BorderLayout.CENTER);

        /* -------- Barra superior -------- */
        JPanel top = new JPanel(new BorderLayout());
        JButton calcBtn = new JButton("Calcular horario");
        calcBtn.setFont(font);
        calcBtn.addActionListener(e -> calcularHorario(font));
        top.add(calcBtn, BorderLayout.WEST);
        comboCounterLabel = new JLabel("Combinaciones comprobadas: 0");
        comboCounterLabel.setFont(font);
        top.add(comboCounterLabel, BorderLayout.EAST);
        frame.add(top, BorderLayout.NORTH);

        /* -------- Área de RESUMEN (derecha del split) -------- */
        outputArea = new JTextArea();
        outputArea.setFont(font);
        outputArea.setEditable(false);
        outputArea.setLineWrap(true);
        outputArea.setWrapStyleWord(true);
        outputArea.setMargin(new Insets(8,10,8,10));
        JScrollPane summaryScroll = new JScrollPane(outputArea);
        summaryScroll.setMinimumSize(new Dimension(700, 300));

        /* -------- SPLIT HORIZONTAL con control deslizante -------- */
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, summaryScroll);
        split.setOneTouchExpandable(true);      // flechas para mover más rápido
        split.setContinuousLayout(true);
        split.setDividerSize(10);
        split.setResizeWeight(0.30);            // al redimensionar, el 70% extra va a la derecha
        split.setDividerLocation(0.35);         // ~35% izquierda / 65% derecha inicial

        // Añadir el split al centro (reemplaza CENTER anterior)
        frame.add(split, BorderLayout.CENTER);

        frame.setSize(1500, 900);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Ajuste final tras mostrar
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.35));
    }

    private void crearCheckDia(JPanel parent, String txt, DayOfWeek d, Font font){
        JCheckBox cb = new JCheckBox(txt);
        cb.setFont(font);
        parent.add(cb);
        freeDayChecks.put(d, cb);
    }

    /* --------------- CÁLCULO DE HORARIO --------------- */
    private void calcularHorario(final Font font) {
        combosTested.val = 0;

        // 1) Recoger asignaturas marcadas
        List<String> seleccionadas = new ArrayList<>();
        for (JToggleButton btn : subjectButtons) if (btn.isSelected()) seleccionadas.add(btn.getActionCommand());
        if (seleccionadas.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Debes seleccionar al menos una asignatura.");
            return;
        }

        // 2) DÍAS LIBRES (múltiples)
        Set<DayOfWeek> diasLibres = new HashSet<>();
        for (Map.Entry<DayOfWeek, JCheckBox> e : freeDayChecks.entrySet())
            if (e.getValue().isSelected()) diasLibres.add(e.getKey());

        // 3) Semestre activo (obligatorio)
        String semAct = Objects.requireNonNull(semesterCombo.getSelectedItem()).toString();
        if (!semAct.equals("1") && !semAct.equals("2")) {
            JOptionPane.showMessageDialog(frame, "Debes seleccionar semestre 1 o 2.");
            return;
        }

        // 4) Filtrar sessions por semestre y asignaturas
        List<Subject> baseSubjects = allSubjects.stream()
                .filter(s -> seleccionadas.contains(s.name()))
                .map(s -> filterSubjectBySemester(s, semAct))
                .filter(Objects::nonNull)
                .toList();

        // (Opcional) aplicar “sin prácticas” si marcaste SP en la UI
        Set<String> sp = new HashSet<>();
        for (String name : seleccionadas) {
            JCheckBox cb = skipPracticesBySubject.get(name);
            if (cb != null && cb.isSelected()) sp.add(name);
        }
        baseSubjects = applySkipPractices(baseSubjects, sp);

        if (baseSubjects.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "No quedan sesiones tras aplicar filtros (semestre/días libres/sin prácticas).");
            return;
        }

        // 5) Bloqueo de subgrupo o mezcla por tipo
        boolean lockSameSubgroup = sameSubgroupBox.isSelected();
        List<Subject> subjects = lockSameSubgroup ? baseSubjects : expandSubjectsByTipo(baseSubjects);

        if (subjects.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "No hay sesiones para esas asignaturas en semestre " + semAct + ".");
            return;
        }

        // 6) Enumerar combinaciones (optimizado)
        List<List<Group>> opciones = subjects.stream().map(Subject::groups).toList();

        // Mapear cada grupo a un id entero y construir dominios como arrays de ids
        List<Group> global = new ArrayList<>();
        List<int[]> domains = new ArrayList<>();
        Map<Group, Integer> idOf = new IdentityHashMap<>();
        for (List<Group> dom : opciones) {
            int[] ids = new int[dom.size()];
            for (int i=0;i<dom.size();i++) {
                Group g = dom.get(i);
                Integer id = idOf.get(g);
                if (id == null) { id = global.size(); idOf.put(g, id); global.add(g); }
                ids[i] = id;
            }
            domains.add(ids);
        }

        // Matriz de conflictos pareados
        final int N = global.size();
        int[][] conflictPairs = new int[N][N];
        for (int i=0;i<N;i++) for (int j=i+1;j<N;j++) {
            int c = countPairwiseConflicts(global.get(i), global.get(j));
            conflictPairs[i][j] = c;
            conflictPairs[j][i] = c;
        }

        // Heurística: ordenar dominios por tamaño (fail-first)
        List<Integer> order = new ArrayList<>();
        for (int i=0;i<domains.size();i++) order.add(i);
        order.sort(Comparator.comparingInt(i -> domains.get(i).length));
        List<int[]> orderedDomains = new ArrayList<>();
        for (int idxOrd : order) orderedDomains.add(domains.get(idxOrd));

        // Heurística: dentro de cada dominio, ordenar valores por “grado de conflicto” ascendente
        for (int[] dom : orderedDomains) {
            Integer[] boxed = Arrays.stream(dom).boxed().toArray(Integer[]::new);
            Arrays.sort(boxed, Comparator.comparingInt(id -> conflictDegree(id, conflictPairs)));
            for (int i = 0; i < dom.length; i++) dom[i] = boxed[i];
        }

        // Zobrist hashing para memo
        long[] zobrist = new long[N];
        Random rnd = new Random(1234567);
        for (int i=0;i<N;i++) zobrist[i] = rnd.nextLong();
        Map<Long, Integer> memo = new HashMap<>();

        // Búsqueda
        List<Integer> bestChoiceIds = new ArrayList<>();
        List<Group> mejor = new ArrayList<>();
        IntRef bestSol = new IntRef(Integer.MAX_VALUE);

        backtrackOpt(0, orderedDomains, new ArrayList<>(), 0, diasLibres, bestSol, bestChoiceIds,
                conflictPairs, global, memo, zobrist);

        for (int id : bestChoiceIds) mejor.add(global.get(id));

        comboCounterLabel.setText("Combinaciones comprobadas: " + combosTested.val);
        if (mejor.isEmpty()) {
            outputArea.setText("No hay combinación válida que respete los días libres y el semestre seleccionados.");
            return;
        }

        // 7) Construir estructuras por día y conflictos
        Map<DayOfWeek, List<Session>> porDia = new TreeMap<>();
        for (Group g : mejor) for (Session s : g.sessions())
            porDia.computeIfAbsent(s.day(), k -> new ArrayList<>()).add(s);
        Set<Session> enConf = detectConflicts(porDia);

        // 8) Mostrar tabla (modal) y listado
        mostrarTabla(porDia, enConf, font);
        outputArea.setText(buildSummary(subjects, mejor, bestSol.val, enConf, semAct));
        outputArea.setCaretPosition(0);
    }

    /* ---------- buildSummary: resumen textual ---------- */
    private static String buildSummary(List<Subject> subjects, List<Group> mejor, int solap, Set<Session> enConf, String semestre) {
        StringBuilder sb = new StringBuilder();
        sb.append("Mejor combinación (Semestre ").append(semestre).append("):\n\n");
        for (int i = 0; i < subjects.size(); i++) {
            Subject subj = subjects.get(i);
            Group g = mejor.get(i);
            sb.append(subj.name()).append(" → Grupo ").append(g.code()).append("\n");
            g.sessions().stream()
                    .sorted(Comparator.comparing(Session::day).thenComparing(Session::start))
                    .forEach(s -> sb.append(String.format("   - %-15s %s %s–%s%n",
                            s.tipo().toUpperCase(), s.day(), s.start(), s.end())));
            sb.append("\n");
        }
        sb.append(solap == 0 ? "✅ Sin solapamientos.\n" : "⚠ Solapamientos totales: " + solap + "\n");
        if (enConf.isEmpty()) return sb.append("  - Detalle solapamientos: - Ninguno").toString();

        sb.append("  - Detalle solapamientos:\n");
        Set<String> done = new HashSet<>();
        List<Session> todas = enConf.stream().toList();
        for (int i = 0; i < todas.size(); i++) {
            for (int j = i+1; j < todas.size(); j++) {
                Session a = todas.get(i), b = todas.get(j);
                if (!a.asignatura().equals(b.asignatura()) && a.overlaps(b)) {
                    String key = a.asignatura()+a.grupo()+b.asignatura()+b.grupo()+a.day();
                    String rev = b.asignatura()+b.grupo()+a.asignatura()+a.grupo()+a.day();
                    if (done.add(key) && done.add(rev)) {
                        sb.append(String.format(
                                "   • %s [%s] %s-%s con %s [%s] %s-%s (%s)%n",
                                a.asignatura(), a.grupo(), a.start(), a.end(),
                                b.asignatura(), b.grupo(), b.start(), b.end(),
                                a.day()));
                    }
                }
            }
        }
        return sb.toString();
    }

    /* --------------- BACKTRACK & HELPERS --------------- */

    private void backtrackOpt(
            int idx,
            List<int[]> domains,
            List<Integer> curIds,
            int curConf,
            Set<DayOfWeek> diasLibres,
            IntRef best,
            List<Integer> bestChoiceIds,
            int[][] conflictPairs,
            List<Group> global,
            Map<Long,Integer> memo,
            long[] zobrist
    ) {
        if (best.val == 0) return;

        long hash = 0L;
        for (int id : curIds) hash ^= zobrist[id];
        long key = (((long)idx) << 32) ^ (hash ^ (hash >>> 32));
        Integer seen = memo.get(key);
        if (seen != null && curConf >= seen) return;
        memo.put(key, curConf);

        if (idx == domains.size()) {
            if (curConf < best.val) {
                best.val = curConf;
                bestChoiceIds.clear();
                bestChoiceIds.addAll(curIds);
            }
            return;
        }

        int[] dom = domains.get(idx);
        for (int id : dom) {
            if (!diasLibres.isEmpty() && groupHasAnyDay(global.get(id), diasLibres)) continue;

            combosTested.val++;
            int inc = 0;
            for (int sel : curIds) inc += conflictPairs[id][sel];
            int next = curConf + inc;
            if (next >= best.val) continue;

            curIds.add(id);
            backtrackOpt(idx+1, domains, curIds, next, diasLibres, best, bestChoiceIds,
                    conflictPairs, global, memo, zobrist);
            curIds.remove(curIds.size()-1);

            if (best.val == 0) return;
        }
    }

    private static Subject filterSubjectBySemester(Subject subj, String semestre) {
        List<Group> gruposFiltrados = new ArrayList<>();
        for (Group g : subj.groups()) {
            List<Session> ses = g.sessions().stream().filter(s -> semestre.equals(s.semestre())).toList();
            if (!ses.isEmpty()) gruposFiltrados.add(new Group(g.code(), ses));
        }
        return gruposFiltrados.isEmpty() ? null : new Subject(subj.name(), gruposFiltrados);
    }

    // Excluir prácticas para las asignaturas marcadas “SP”
    private static List<Subject> applySkipPractices(List<Subject> baseSubjects, Set<String> spSubjects){
        if (spSubjects.isEmpty()) return baseSubjects;
        List<Subject> out = new ArrayList<>();
        for (Subject subj : baseSubjects) {
            if (!spSubjects.contains(subj.name())) { out.add(subj); continue; }
            List<Group> filteredGroups = new ArrayList<>();
            for (Group g : subj.groups()) {
                List<Session> keep = new ArrayList<>();
                for (Session s : g.sessions()) {
                    String t = normalizeTipo(s.tipo());
                    if (!"Prácticas".equals(t) && !"Prácticas aula".equals(t)) keep.add(s);
                }
                if (!keep.isEmpty()) filteredGroups.add(new Group(g.code(), keep));
            }
            if (!filteredGroups.isEmpty()) out.add(new Subject(subj.name(), filteredGroups));
        }
        return out;
    }

    private static int conflictDegree(int id, int[][] conflictPairs){
        int sum = 0;
        for (int x : conflictPairs[id]) sum += x;
        return sum;
    }

    private static int countPairwiseConflicts(Group a, Group b){
        int c = 0;
        for (Session sa : a.sessions())
            for (Session sb : b.sessions())
                if (sa.overlaps(sb)) c++;
        return c;
    }

    private static boolean groupHasAnyDay(Group g, Set<DayOfWeek> dias){
        for (Session s : g.sessions()) if (dias.contains(s.day())) return true;
        return false;
    }

    /* ------------------ GUI: Tabla ------------------ */
    private void mostrarTabla(Map<DayOfWeek, List<Session>> porDia, Set<Session> enConf, Font font) {
        LocalTime min = LocalTime.of(23,59), max = LocalTime.of(0,0);
        for (List<Session> ls : porDia.values())
            for (Session s : ls) { if (s.start().isBefore(min)) min = s.start(); if (s.end().isAfter(max)) max = s.end(); }
        min = min.withMinute(0);
        if (max.getMinute() > 0) max = max.plusHours(1).withMinute(0);
        int rows = Math.max(1, max.getHour() - min.getHour());

        String[] cols = {"Hora","Lun","Mar","Mié","Jue","Vie"};
        Object[][] data = new Object[rows][6];
        Map<Point, List<Session>> cellSessions = new HashMap<>();

        DayOfWeek[] dias = {DayOfWeek.MONDAY,DayOfWeek.TUESDAY,DayOfWeek.WEDNESDAY,DayOfWeek.THURSDAY,DayOfWeek.FRIDAY};
        for (int r=0;r<rows;r++) {
            LocalTime slotStart = min.plusHours(r), slotEnd = slotStart.plusHours(1);
            data[r][0] = String.format("%02d:%02d-%02d:%02d", slotStart.getHour(),0, slotEnd.getHour(),0);
            for (int d=0; d<dias.length; d++) {
                List<Session> inCell = new ArrayList<>();
                for (Session s : porDia.getOrDefault(dias[d], List.of())) {
                    if (s.start().isBefore(slotEnd) && s.end().isAfter(slotStart)) {
                        inCell.add(s);
                    }
                }
                if (!inCell.isEmpty()) {
                    cellSessions.put(new Point(r,d+1), inCell);
                    data[r][d+1] = inCell.stream().map( s->s.curso()+"º"+s.asignatura()+"["+s.grupo()+"]").reduce((a,b)->a+", "+b).orElse("");
                } else data[r][d+1] = "";
            }
        }

        JTable table = new JTable(new DefaultTableModel(data, cols) { public boolean isCellEditable(int r,int c){return false;} });
        table.setFont(font);
        table.setRowHeight(font.getSize()+16);
        table.getTableHeader().setFont(font.deriveFont(Font.BOLD));

        // Renderer (colores y bandas de conflicto)
        TableCellRenderer rend = new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable t,Object val,boolean sel,boolean foc,int row,int col){
                Component c = super.getTableCellRendererComponent(t,val,sel,foc,row,col);
                if (col==0){
                    c.setBackground(Color.WHITE);
                    if (c instanceof JComponent jc)
                        jc.setBorder(BorderFactory.createMatteBorder(1,1,1,1,new Color(200,200,200)));
                    return c;
                }
                List<Session> ss = cellSessions.get(new Point(row,col));
                if (ss!=null && ss.size()>1) {
                    return new SplitCellPanel(ss, enConf, font);
                } else if (ss!=null && ss.size()==1) {
                    Session s = ss.get(0);
                    toLowerCase(c, s);
                    if (c instanceof JComponent jc){
                        boolean conflict = enConf.contains(s);
                        int top = conflict ? 3 : 1;
                        jc.setBorder(BorderFactory.createMatteBorder(top,1,1,1, conflict ? new Color(255,235,59) : new Color(200,200,200)));
                    }
                } else if (c instanceof JComponent jc) {
                    jc.setBackground(Color.WHITE);
                    jc.setBorder(BorderFactory.createMatteBorder(1,1,1,1,new Color(200,200,200)));
                }
                return c;
            }
        };
        for(int i=0;i<table.getColumnCount();i++) table.getColumnModel().getColumn(i).setCellRenderer(rend);

        // --- Tabla ancha ---
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        TableColumnModel cm = table.getColumnModel();
        int timeW = 100;       // Hora
        int dayW  = 240;       // cada día
        cm.getColumn(0).setPreferredWidth(timeW);
        for (int i=1;i<cm.getColumnCount();i++) cm.getColumn(i).setPreferredWidth(dayW);

        int totalWidth  = timeW + dayW * (cm.getColumnCount()-1) + 40; // margen scroll
        int totalHeight = Math.min(900, (rows+1)*(font.getSize()+16)+120);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(totalWidth, totalHeight));

        JDialog dlg = new JDialog(frame, "Horario", true);
        dlg.getContentPane().add(scroll);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
    }

    private static void toLowerCase(Component c, Session s) {
        String tipo = s.tipo().toLowerCase();
        Color base = Color.WHITE;
        if (tipo.contains("prácticas aula") || tipo.contains("practicas aula")) base = new Color(173,216,230);
        else if (tipo.contains("prácticas") || tipo.contains("practicas")) base = new Color(144,238,144);
        c.setBackground(base);
    }

    static class SplitCellPanel extends JPanel {
        SplitCellPanel(List<Session> ss, Set<Session> enConf, Font font){
            setLayout(new GridLayout(1, Math.max(1, ss.size()), 1, 0));
            setBorder(BorderFactory.createMatteBorder(1,1,1,1,new Color(200,200,200)));
            for (Session s: ss){
                JPanel slot = new JPanel(new BorderLayout());
                toLowerCase(slot, s);
                if (enConf.contains(s)){
                    JPanel stripe = new JPanel();
                    stripe.setPreferredSize(new Dimension(1,3));
                    stripe.setBackground(new Color(255,235,59));
                    slot.add(stripe, BorderLayout.NORTH);
                }
                JLabel lbl = new JLabel(s.curso()+"º"+s.asignatura()+"["+s.grupo()+"]", SwingConstants.CENTER);
                lbl.setFont(font.deriveFont(Font.PLAIN, Math.max(10, font.getSize()-2)));
                lbl.setOpaque(false);
                slot.add(lbl, BorderLayout.CENTER);
                slot.setBorder(BorderFactory.createMatteBorder(0,0,0,0,new Color(200,200,200)));
                add(slot);
            }
        }
    }

    /* ------------------ JSON & Utils ------------------ */
    private static List<Session> loadEntries(File file) throws Exception {
        ObjectMapper map = new ObjectMapper();
        map.configure(JsonParser.Feature.ALLOW_COMMENTS,true);
        List<JsonEntry> entries = map.readValue(file, new TypeReference<>(){});
        List<Session> out = new ArrayList<>();
        for (JsonEntry e : entries) {
            String code = e.grupo() + (e.subgrupo()==null?"":"-"+e.subgrupo());
            DayOfWeek day = parseDay(e.dia());
            LocalTime st = LocalTime.parse(e.inicio(), DateTimeFormatter.ofPattern("H:mm"));
            LocalTime en = LocalTime.parse(e.fin(),    DateTimeFormatter.ofPattern("H:mm"));
            out.add(new Session(day, st, en, e.asignatura(), code, e.tipo(), e.curso(), e.semestre()));
        }
        return out;
    }

    private static List<Subject> buildSubjects(List<Session> sessions) {
        Map<String, Map<String, List<Session>>> tmp = new LinkedHashMap<>();
        for (Session s : sessions)
            tmp.computeIfAbsent(s.asignatura(),k->new LinkedHashMap<>())
                    .computeIfAbsent(s.grupo(),k->new ArrayList<>()).add(s);
        List<Subject> res = new ArrayList<>();
        tmp.forEach((asig,map)->{
            List<Group> gl = new ArrayList<>();
            map.forEach((code,list)->gl.add(new Group(code,list)));
            res.add(new Subject(asig,gl));
        });
        return res;
    }

    private static DayOfWeek parseDay(String t) {
        return switch(t.toLowerCase(Locale.ROOT)){
            case "lunes","lun" -> DayOfWeek.MONDAY;
            case "martes","mar" -> DayOfWeek.TUESDAY;
            case "miércoles","miercoles","mié","mie" -> DayOfWeek.WEDNESDAY;
            case "jueves","jue" -> DayOfWeek.THURSDAY;
            case "viernes","vie" -> DayOfWeek.FRIDAY;
            default -> throw new IllegalArgumentException("Día inválido: "+t);
        };
    }

    private static Set<Session> detectConflicts(Map<DayOfWeek, List<Session>> porDia) {
        Set<Session> res = new HashSet<>();
        List<Session> all = porDia.values().stream().flatMap(List::stream).toList();
        for(int i=0;i<all.size();i++)
            for(int j=i+1;j<all.size();j++)
                if(all.get(i).overlaps(all.get(j))) { res.add(all.get(i)); res.add(all.get(j)); }
        return res;
    }

    /* ---------- Mezcla de subgrupos por tipo ---------- */
    private static String normalizeTipo(String tipoRaw){
        if (tipoRaw == null) return "Teoría";
        String t = tipoRaw.toLowerCase(Locale.ROOT);
        if (t.contains("prácticas aula") || t.contains("practicas aula")) return "Prácticas aula";
        if (t.contains("prácticas")      || t.contains("practicas"))      return "Prácticas";
        return "Teoría";
    }

    /**
     * Expande cada asignatura en varias “sub-asignaturas” por tipo de sesión.
     * Resultado: por cada (Asignatura, Tipo) se elige 1 grupo de ese tipo (permite mezclar subgrupos).
     */
    private static List<Subject> expandSubjectsByTipo(List<Subject> baseSubjects){
        List<Subject> out = new ArrayList<>();
        for (Subject subj : baseSubjects) {
            Map<String, Map<String, List<Session>>> byTipo = new LinkedHashMap<>();
            for (Group g : subj.groups()) {
                for (Session s : g.sessions()) {
                    String tipo = normalizeTipo(s.tipo());
                    byTipo.computeIfAbsent(tipo, k -> new LinkedHashMap<>())
                            .computeIfAbsent(g.code(), k -> new ArrayList<>())
                            .add(s);
                }
            }
            for (Map.Entry<String, Map<String, List<Session>>> e : byTipo.entrySet()) {
                String tipo = e.getKey();
                List<Group> groups = new ArrayList<>();
                for (Map.Entry<String, List<Session>> ge : e.getValue().entrySet()) {
                    groups.add(new Group(ge.getKey(), ge.getValue()));
                }
                out.add(new Subject(subj.name() + " [" + tipo + "]", groups));
            }
        }
        return out;
    }

    /* ---------- Helpers ---------- */
    public record JsonEntry(
            String asignatura, String grupo, String subgrupo,
            String tipo, String dia, String inicio, String fin, String curso, String semestre) {}
    private static class IntRef { int val; IntRef(int v){val=v;} }
}
