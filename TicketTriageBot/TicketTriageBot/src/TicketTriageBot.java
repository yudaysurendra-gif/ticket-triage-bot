import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Ticket Triage Bot - a simple rule-based support ticket classifier.
 *
 * It reads a ticket (subject + description), then automatically:
 *   1. Detects the CATEGORY   (Billing, Technical, Account, Security, General)
 *   2. Assigns a PRIORITY     (Critical, High, Medium, Low)
 *   3. Routes it to a TEAM
 *   4. Suggests an auto-reply
 *   5. Keeps tickets in a priority queue so the most urgent is handled first
 *
 * Compile: javac TicketTriageBot.java
 * Run:     java TicketTriageBot
 * Requires Java 8+ (no external libraries).
 */
public class TicketTriageBot {

    // ---------- Enums ----------
    enum Category {
        BILLING("Billing Team"),
        TECHNICAL("Tech Support Team"),
        ACCOUNT("Account Services"),
        SECURITY("Security Team"),
        GENERAL("General Support");

        final String team;
        Category(String team) { this.team = team; }
    }

    enum Priority {
        CRITICAL(1, "1 hour"),
        HIGH(2, "4 hours"),
        MEDIUM(3, "1 business day"),
        LOW(4, "3 business days");

        final int rank;
        final String sla;
        Priority(int rank, String sla) { this.rank = rank; this.sla = sla; }
    }

    enum Status { OPEN, RESOLVED }

    // ---------- Ticket model ----------
    static class Ticket {
        private static int counter = 1000;

        final int id;
        final String customer;
        final String subject;
        final String description;
        final LocalDateTime createdAt;
        Category category;
        Priority priority;
        Status status = Status.OPEN;

        Ticket(String customer, String subject, String description) {
            this.id = ++counter;
            this.customer = customer;
            this.subject = subject;
            this.description = description;
            this.createdAt = LocalDateTime.now();
        }

        String shortLine() {
            return String.format("#%d | %-8s | %-9s | %-17s | %s",
                    id, priority, category, category.team, subject);
        }
    }

    // ---------- Triage engine (the "bot") ----------
    static class TriageEngine {
        private final Map<Category, List<String>> categoryKeywords = new LinkedHashMap<>();
        private final List<String> criticalWords = Arrays.asList(
                "outage", "down", "data loss", "breach", "hacked", "production", "urgent", "emergency");
        private final List<String> highWords = Arrays.asList(
                "cannot", "can't", "unable", "failed", "error", "crash", "charged twice", "locked", "asap");
        private final List<String> mediumWords = Arrays.asList(
                "slow", "issue", "problem", "wrong", "not working", "delay");

        TriageEngine() {
            categoryKeywords.put(Category.SECURITY, Arrays.asList(
                    "hacked", "breach", "phishing", "unauthorized", "suspicious", "stolen", "malware"));
            categoryKeywords.put(Category.BILLING, Arrays.asList(
                    "invoice", "refund", "charge", "charged", "payment", "billing", "subscription", "price"));
            categoryKeywords.put(Category.ACCOUNT, Arrays.asList(
                    "password", "login", "log in", "account", "email change", "locked", "profile", "sign in"));
            categoryKeywords.put(Category.TECHNICAL, Arrays.asList(
                    "error", "bug", "crash", "slow", "down", "outage", "not working", "api", "server", "app"));
        }

        Category detectCategory(String text) {
            Category best = Category.GENERAL;
            int bestScore = 0;
            for (Map.Entry<Category, List<String>> e : categoryKeywords.entrySet()) {
                int score = countMatches(text, e.getValue());
                if (score > bestScore) {   // first category wins ties (Security is checked first)
                    bestScore = score;
                    best = e.getKey();
                }
            }
            return best;
        }

        Priority detectPriority(String text, Category category) {
            int score = countMatches(text, criticalWords) * 3
                      + countMatches(text, highWords) * 2
                      + countMatches(text, mediumWords);

            Priority p;
            if (score >= 3)      p = Priority.CRITICAL;
            else if (score >= 2) p = Priority.HIGH;
            else if (score >= 1) p = Priority.MEDIUM;
            else                 p = Priority.LOW;

            // Security tickets are never lower than HIGH
            if (category == Category.SECURITY && p.rank > Priority.HIGH.rank) {
                p = Priority.HIGH;
            }
            return p;
        }

        void triage(Ticket t) {
            String text = (t.subject + " " + t.description).toLowerCase();
            t.category = detectCategory(text);
            t.priority = detectPriority(text, t.category);
        }

        String suggestReply(Ticket t) {
            String base = "Hi " + t.customer + ", thanks for contacting us. ";
            switch (t.category) {
                case BILLING:
                    base += "Our Billing Team is reviewing your payment query.";
                    break;
                case TECHNICAL:
                    base += "Our engineers are looking into the technical issue.";
                    break;
                case ACCOUNT:
                    base += "Account Services will help you regain or update access.";
                    break;
                case SECURITY:
                    base += "We take security seriously. Please change your password now while our Security Team investigates.";
                    break;
                default:
                    base += "A support agent will get back to you shortly.";
            }
            return base + " Expected response time: " + t.priority.sla + ".";
        }

        private int countMatches(String text, List<String> keywords) {
            int count = 0;
            for (String k : keywords) {
                if (text.contains(k)) count++;
            }
            return count;
        }
    }

    // ---------- Application state ----------
    private final TriageEngine engine = new TriageEngine();
    private final List<Ticket> allTickets = new ArrayList<>();
    // Most urgent first; ties broken by oldest first
    private final PriorityQueue<Ticket> queue = new PriorityQueue<>(
            Comparator.<Ticket>comparingInt(t -> t.priority.rank)
                      .thenComparing(t -> t.createdAt));
    private final Scanner in = new Scanner(System.in);

    private Ticket submit(String customer, String subject, String description) {
        Ticket t = new Ticket(customer, subject, description);
        engine.triage(t);
        allTickets.add(t);
        queue.add(t);
        return t;
    }

    private void printTicketDetails(Ticket t) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");
        System.out.println("--------------------------------------------");
        System.out.println("Ticket    : #" + t.id + " (" + t.status + ")");
        System.out.println("Customer  : " + t.customer);
        System.out.println("Subject   : " + t.subject);
        System.out.println("Created   : " + t.createdAt.format(f));
        System.out.println("Category  : " + t.category);
        System.out.println("Priority  : " + t.priority + "  (SLA: " + t.priority.sla + ")");
        System.out.println("Assigned  : " + t.category.team);
        System.out.println("Auto-reply: " + engine.suggestReply(t));
        System.out.println("--------------------------------------------");
    }

    // ---------- Menu actions ----------
    private void submitTicket() {
        System.out.print("Customer name : ");
        String customer = in.nextLine().trim();
        System.out.print("Subject       : ");
        String subject = in.nextLine().trim();
        System.out.print("Description   : ");
        String desc = in.nextLine().trim();

        if (customer.isEmpty() || subject.isEmpty()) {
            System.out.println("Customer and subject are required.");
            return;
        }
        Ticket t = submit(customer, subject, desc);
        System.out.println("\nTicket created and triaged!");
        printTicketDetails(t);
    }

    private void viewQueue() {
        if (queue.isEmpty()) {
            System.out.println("Queue is empty. Nice work!");
            return;
        }
        List<Ticket> sorted = new ArrayList<>(queue);
        sorted.sort(queue.comparator());
        System.out.println("\nOPEN TICKETS (most urgent first)");
        for (Ticket t : sorted) System.out.println(t.shortLine());
    }

    private void processNext() {
        Ticket t = queue.poll();
        if (t == null) {
            System.out.println("No tickets to process.");
            return;
        }
        t.status = Status.RESOLVED;
        System.out.println("\nProcessing the most urgent ticket:");
        printTicketDetails(t);
        System.out.println("Marked as RESOLVED.");
    }

    private void showSummary() {
        Map<Category, Integer> byCat = new EnumMap<>(Category.class);
        Map<Priority, Integer> byPri = new EnumMap<>(Priority.class);
        for (Ticket t : allTickets) {
            byCat.merge(t.category, 1, Integer::sum);
            byPri.merge(t.priority, 1, Integer::sum);
        }
        System.out.println("\nSUMMARY");
        System.out.println("Total tickets : " + allTickets.size());
        System.out.println("Open          : " + queue.size());
        System.out.println("By category   : " + byCat);
        System.out.println("By priority   : " + byPri);
    }

    private void loadSamples() {
        submit("Asha", "Production server down", "Our production API is down since morning, urgent help needed");
        submit("Ravi", "Charged twice", "I was charged twice for my subscription, need a refund asap");
        submit("Meena", "Cannot login", "Unable to sign in, it says password is wrong and my account is locked");
        submit("John", "Suspicious activity", "I see unauthorized logins, I think my account was hacked");
        submit("Priya", "Question about features", "Does the pro plan include dark mode?");
        System.out.println("Loaded 5 sample tickets.");
    }

    // ---------- Main loop ----------
    private void run() {
        System.out.println("=== Ticket Triage Bot ===");
        boolean running = true;
        while (running) {
            System.out.println("\n1. Submit new ticket");
            System.out.println("2. View open queue");
            System.out.println("3. Process next (most urgent)");
            System.out.println("4. Summary");
            System.out.println("5. Load sample tickets");
            System.out.println("0. Exit");
            System.out.print("Choose: ");

            switch (in.nextLine().trim()) {
                case "1": submitTicket(); break;
                case "2": viewQueue(); break;
                case "3": processNext(); break;
                case "4": showSummary(); break;
                case "5": loadSamples(); break;
                case "0": running = false; break;
                default:  System.out.println("Invalid option.");
            }
        }
        System.out.println("Goodbye!");
    }

    public static void main(String[] args) {
        new TicketTriageBot().run();
    }
}
