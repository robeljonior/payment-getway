package com.superup.gateway.record;

import com.superup.gateway.common.Ids;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Layer 4: the double-entry ledger (docs/05-money-and-ledger.md).
 * A journal is a set of lines whose debits equal its credits. The database checks this at commit,
 * and journals can never be changed: a correction is a new journal.
 */
@Repository
public class Ledger {

    public enum Direction { debit, credit }

    public record Line(String accountCode, String accountType, Direction direction, long amount) {
        public static Line debit(String code, String type, long amount) {
            return new Line(code, type, Direction.debit, amount);
        }

        public static Line credit(String code, String type, long amount) {
            return new Line(code, type, Direction.credit, amount);
        }
    }

    private static final ZoneId ADDIS_ABABA = ZoneId.of("Africa/Addis_Ababa");

    private final JdbcTemplate db;

    public Ledger(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * Posts one journal and returns its ID. Lines with a zero amount are skipped.
     * sourceEventId is unique, so the same business event can never be posted twice.
     */
    public String post(String type, String sourceEventId, String mode, List<Line> lines) {
        String journalId = Ids.newId(Ids.JOURNAL);
        db.update("""
                INSERT INTO ledger_journals (id, source_event_id, type, mode, value_date) VALUES (?, ?, ?, ?, ?)
                """, journalId, sourceEventId, type, mode, LocalDate.now(ADDIS_ABABA));
        for (Line line : lines) {
            if (line.amount() == 0) {
                continue;
            }
            long accountId = account(line.accountCode(), line.accountType(), mode);
            db.update("""
                    INSERT INTO ledger_entries (journal_id, account_id, direction, amount, currency)
                    VALUES (?, ?, ?, ?, 'ETB')
                    """, journalId, accountId, line.direction().name(), line.amount());
        }
        return journalId;
    }

    /** The balance of one account: debits minus credits (positive for assets). */
    public long balance(String accountCode, String mode) {
        Long value = db.queryForObject("""
                SELECT coalesce(sum(CASE WHEN e.direction = 'debit' THEN e.amount ELSE -e.amount END), 0)
                  FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.account_id
                 WHERE a.code = ? AND a.mode = ?
                """, Long.class, accountCode, mode);
        return value == null ? 0 : value;
    }

    private long account(String code, String type, String mode) {
        db.update("""
                INSERT INTO ledger_accounts (code, mode, type, currency) VALUES (?, ?, ?, 'ETB')
                ON CONFLICT (code, mode) DO NOTHING
                """, code, mode, type);
        return db.queryForObject("SELECT id FROM ledger_accounts WHERE code = ? AND mode = ?", Long.class, code, mode);
    }
}
