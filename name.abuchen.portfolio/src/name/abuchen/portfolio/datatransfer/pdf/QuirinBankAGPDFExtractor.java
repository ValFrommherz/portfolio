package name.abuchen.portfolio.datatransfer.pdf;

import static name.abuchen.portfolio.datatransfer.pdf.PDFExtractorUtils.checkAndSetGrossUnit;
import static name.abuchen.portfolio.util.TextUtil.stripBlanks;
import static name.abuchen.portfolio.util.TextUtil.trim;

import name.abuchen.portfolio.datatransfer.pdf.PDFParser.Block;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.DocumentType;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.Transaction;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.money.Money;

@SuppressWarnings("nls")
public class QuirinBankAGPDFExtractor extends AbstractPDFExtractor
{
    public QuirinBankAGPDFExtractor(Client client)
    {
        super(client);

        addBankIdentifier("quirin bank AG"); //$NON-NLS-1$
        addBankIdentifier("Quirin Privatbank AG"); //$NON-NLS-1$

        addBuySellTransaction_Format01();
        addBuySellTransaction_Format02();
        addDividendeTransaction_Format01();
        addDividendeTransaction_Format02();
        addDepotStatementTransaction();
    }

    @Override
    public String getLabel()
    {
        return "Quirin Privatbank AG"; //$NON-NLS-1$
    }

    private void addBuySellTransaction_Format01()
    {
        DocumentType type = new DocumentType("Wertpapierabrechnung");
        this.addDocumentTyp(type);

        Transaction<BuySellEntry> pdfTransaction = new Transaction<>();
        pdfTransaction.subject(() -> {
            BuySellEntry entry = new BuySellEntry();
            entry.setType(PortfolioTransaction.Type.BUY);
            return entry;
        });

        Block firstRelevantLine = new Block("^Wertpapierbezeichnung .*$");
        type.addBlock(firstRelevantLine);
        firstRelevantLine.set(pdfTransaction);

        pdfTransaction
                // Is type --> "Verkauf" change from BUY to SELL
                .section("type").optional()
                .match("^(?<type>(Kauf|Verkauf))$")
                .assign((t, v) -> {
                    if (v.get("type").equals("Verkauf"))
                        t.setType(PortfolioTransaction.Type.SELL);
                })

                // Wertpapierbezeichnung db x-tr.II Gl Sovereign ETF Inhaber-Anteile 1D EUR o.N.
                // ISIN LU0690964092
                // WKN DBX0MF
                // Kurs EUR 214,899
                .section("name", "isin", "wkn", "currency")
                .match("^Wertpapierbezeichnung (?<name>.*)$")
                .match("^ISIN (?<isin>[A-Z]{2}[A-Z0-9]{9}[0-9])$")
                .match("^WKN (?<wkn>[A-Z0-9]{6})$")
                .match("^Kurs (?<currency>[\\w]{3}) [\\.,\\d]+$")
                .assign((t, v) -> t.setSecurity(getOrCreateSecurity(v)))

                // Nominal / Stück 140,0000 ST
                .section("shares")
                .match("^Nominal \\/ St.ck (?<shares>[\\.,\\d]+) ST$")
                .assign((t, v) -> t.setShares(asShares(v.get("shares"))))
                // Handelstag / Zeit 30.12.2016 12:46:28
                .section("date", "time").optional()
                .match("^Handelstag \\/ Zeit (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) (?<time>[\\d]{2}:[\\d]{2}:[\\d]{2})$")
                .assign((t, v) -> t.setDate(asDate(v.get("date"), v.get("time"))))

                // Ausmachender Betrag EUR - 30.090,76
                .section("currency", "amount")
                .match("^Ausmachender Betrag (?<currency>[\\w]{3}) (\\- )?(?<amount>[\\.,\\d]+)$")
                .assign((t, v) -> {
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                    t.setAmount(asAmount(v.get("amount")));
                })

                // Referenz-Nr 28522373
                .section("note").optional()
                .match("^(?<note>Referenz-Nr .*)$")
                .assign((t, v) -> t.setNote(trim(v.get("note"))))

                .wrap(t -> {
                    if (t.getPortfolioTransaction().getCurrencyCode() != null && t.getPortfolioTransaction().getAmount() != 0)
                        return new BuySellEntryItem(t);
                    return null;
                });

        addTaxesSectionsTransaction(pdfTransaction, type);
        addFeesSectionsTransaction(pdfTransaction, type);
    }

    private void addBuySellTransaction_Format02()
    {
        DocumentType type = new DocumentType("Abrechnungskonditionen");
        this.addDocumentTyp(type);

        Transaction<BuySellEntry> pdfTransaction = new Transaction<>();
        pdfTransaction.subject(() -> {
            BuySellEntry entry = new BuySellEntry();
            entry.setType(PortfolioTransaction.Type.BUY);
            return entry;
        });

        Block firstRelevantLine = new Block("^(Kauf|Verkauf)$");
        type.addBlock(firstRelevantLine);
        firstRelevantLine.set(pdfTransaction);

        pdfTransaction
                // Is type --> "Verkauf" change from BUY to SELL
                .section("type").optional()
                .match("^(?<type>(Kauf|Verkauf))$")
                .assign((t, v) -> {
                    if (v.get("type").equals("Verkauf"))
                        t.setType(PortfolioTransaction.Type.SELL);
                })

                // Nominal/Stück Bayer.Hypo- und Vereinsbank AG DAX Indexzert(2006/unlim.)
                // ST 22 ISIN DE0007873200
                // Abrechnungskonditionen: Abrechnungswerte:
                // Kurs  57,5000 EUR Kurswert  1.265,00 EUR
                //
                // Nominal/Stück Hewlett-Packard Co. Registered Shares DL -,01
                // ST 150 ISIN US4282361033
                // Kurs 39,99667 USD Kurswert -5.999,50 USD -4.734,08 EUR 
                .section("name", "isin", "currency")
                .match("^Nominal\\/St.ck (?<name>.*)$")
                .match("^ST [\\.,\\d]+ ISIN (?<isin>[A-Z]{2}[A-Z0-9]{9}[0-9])$")
                .match("^Kurs ([\\s])?[\\.,\\d]+ (?<currency>[\\w]{3}) .*$")
                .assign((t, v) -> t.setSecurity(getOrCreateSecurity(v)))

                // ST 22 ISIN DE0007873200
                .section("shares")
                .match("^ST (?<shares>[\\.,\\d]+) ISIN (?<isin>[A-Z]{2}[A-Z0-9]{9}[0-9])$")
                .assign((t, v) -> t.setShares(asShares(v.get("shares"))))

                .oneOf(
                                // Ursprüngl. Handelstag 20.08.2010   15:46:50
                                section -> section
                                        .attributes("date", "time")
                                        .match("^Urspr.ngl\\. Handelstag (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) .*(?<time>[\\d]{2}:[\\d]{2}:[\\d]{2})$")
                                        .assign((t, v) -> t.setDate(asDate(v.get("date"), v.get("time"))))
                                ,
                                // Handelstag/-zeit 23.11.2009   11:00:15 Bank-Provision  0,00 EUR
                                // Handelstag/-zeit 20.08.2010 15:46:50 Bank-Provision  0,00 EUR
                                section -> section
                                        .attributes("date", "time")
                                        .match("^Handelstag\\/\\-zeit (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) .*(?<time>[\\d]{2}:[\\d]{2}:[\\d]{2}) .*$")
                                        .assign((t, v) -> t.setDate(asDate(v.get("date"), v.get("time"))))
                                ,
                                // Handelstag/-zeit 23.11.2009 Bank-Provision  0,00 EUR
                                section -> section
                                        .attributes("date")
                                        .match("^Handelstag\\/\\-zeit (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) .*$")
                                        .assign((t, v) -> t.setDate(asDate(v.get("date"))))
                        )

                .oneOf(
                                // Keine Steuerbescheinigung! Ausmachender Betrag  vor Steuern  1.261,61 EUR
                                section -> section
                                        .attributes("amount", "currency")
                                        .match("^.* Ausmachender Betrag .* (?<amount>[\\.,\\d]+) (?<currency>[\\w]{3})$")
                                        .assign((t, v) -> {
                                            t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                                            t.setAmount(asAmount(v.get("amount")));
                                        })
                                ,
                                // Den Gesamtbetrag werden wir mit Valuta 12.02.2010 auf Ihrem Ausmachender Betrag -3.997,09 EUR
                                section -> section
                                        .attributes("currency", "amount")
                                        .match("^.* Ausmachender Betrag \\-(?<amount>[\\.,\\d]+) (?<currency>[\\w]{3})$")
                                        .assign((t, v) -> {
                                            t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                                            t.setAmount(asAmount(v.get("amount")));
                                        })
                        )

                // Kurs 39,99667 USD Kurswert -5.999,50 USD -4.734,08 EUR 
                // Devisenkurs  1,267300
                .section("fxGross", "fxCurrency", "gross", "currency", "exchangeRate").optional()
                .match("^.* Kurswert ([\\-\\s])?(?<fxGross>[\\.,\\d]+) (?<fxCurrency>[\\w]{3}) ([\\-\\s])?(?<gross>[\\.,\\d]+) (?<currency>[\\w]{3}).*$")
                .match("^Devisenkurs ([\\s])?(?<exchangeRate>[\\.,\\d]+).*$")
                .assign((t, v) -> {
                    v.put("baseCurrency", asCurrencyCode(v.get("currency")));
                    v.put("termCurrency", asCurrencyCode(v.get("fxCurrency")));

                    type.getCurrentContext().putType(asExchangeRate(v));

                    Money gross = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("gross")));
                    Money fxGross = Money.of(asCurrencyCode(v.get("fxCurrency")), asAmount(v.get("fxGross")));

                    checkAndSetGrossUnit(gross, fxGross, t, type);
                })

                // Referenz O:000409887:1
                .section("note").optional()
                .match("^(?<note>Referenz .*)$")
                .assign((t, v) -> t.setNote(trim(v.get("note"))))

                .wrap(t -> {
                    if (t.getPortfolioTransaction().getCurrencyCode() != null && t.getPortfolioTransaction().getAmount() != 0)
                        return new BuySellEntryItem(t);
                    return null;
                });

        addTaxesSectionsTransaction(pdfTransaction, type);
        addFeesSectionsTransaction(pdfTransaction, type);
    }

    private void addDividendeTransaction_Format01()
    {
        DocumentType type = new DocumentType("Ertr.gnisabrechnung");
        this.addDocumentTyp(type);

        Block block = new Block("^F.r aus Ihrem Depot f.llig gewordene Ertr.gnisse .*$");
        type.addBlock(block);
        Transaction<AccountTransaction> pdfTransaction = new Transaction<AccountTransaction>().subject(() -> {
            AccountTransaction entry = new AccountTransaction();
            entry.setType(AccountTransaction.Type.DIVIDENDS);
            return entry;
        });

        pdfTransaction
                // Wertpapierbezeichnung iShare.EURO STOXX UCITS ETF DE Inhaber-Anteile
                // ISIN DE000A0D8Q07
                // WKN A0D8Q0
                // Ausschüttung EUR 0,60174 pro Anteil
                .section("name", "isin", "wkn", "currency").optional()
                .match("^Wertpapierbezeichnung (?<name>.*)$")
                .match("^ISIN (?<isin>[A-Z]{2}[A-Z0-9]{9}[0-9])$")
                .match("^WKN (?<wkn>[A-Z0-9]{6})$")
                .match("^Aussch.ttung (?<currency>[\\w]{3}) [\\.,\\d]+ .*$")
                .assign((t, v) -> t.setSecurity(getOrCreateSecurity(v)))

                // Nominal/Stück 700 ST
                .section("shares").optional()
                .match("^Nominal\\/St.ck (?<shares>[\\.,\\d]+) ST$")
                .assign((t, v) -> t.setShares(asShares(v.get("shares"))))

                // Zahlungstag 16.09.2019
                .section("date")
                .match("^Zahlungstag (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4})$")
                .assign((t, v) -> t.setDateTime(asDate(v.get("date"))))

                // Ausmachender Betrag EUR 343,46
                .section("currency", "amount")
                .match("^Ausmachender Betrag (?<currency>[\\w]{3}) (?<amount>[\\.,\\d]+)$")
                .assign((t, v) -> {
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                    t.setAmount(asAmount(v.get("amount")));
                })

                // Dividenden für 01.01.2009-31.12.2009 Bruttobetrag  163,08 USD  124,50 EUR
                // Devisenkurs  1,309900  
                .section("baseCurrency", "termCurrency", "exchangeRate", "fxGross", "fxCurrency", "gross", "currency").optional()
                .match("^(Umrechnungskurs|Exchange Rate): (?<baseCurrency>[\\w]{3})\\/(?<termCurrency>[\\w]{3}) (?<exchangeRate>[\\.,\\d]+)$")
                .match("^(Bruttobetrag|Gross Amount) (?<fxCurrency>[\\w]{3}) (?<fxGross>[\\.,\\d]+)$")
                .match("^(Bruttobetrag|Gross Amount) (?<currency>[\\w]{3}) (?<gross>[\\.,\\d]+)$")
                .assign((t, v) -> {
                    type.getCurrentContext().putType(asExchangeRate(v));

                    Money gross = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("gross")));
                    Money fxGross = Money.of(asCurrencyCode(v.get("fxCurrency")), asAmount(v.get("fxGross")));

                    checkAndSetGrossUnit(gross, fxGross, t, type);
                })

                // Referenz-Nr 28522373
                .section("note").optional()
                .match("^(?<note>Referenz-Nr .*)$")
                .assign((t, v) -> t.setNote(trim(v.get("note"))))

                .wrap(t -> {
                    if (t.getCurrencyCode() != null && t.getAmount() != 0)
                        return new TransactionItem(t);
                    return null;
                });

        addTaxesSectionsTransaction(pdfTransaction, type);
        addFeesSectionsTransaction(pdfTransaction, type);

        block.set(pdfTransaction);
    }

    private void addDividendeTransaction_Format02()
    {
        DocumentType type = new DocumentType("Dividendenabrechnung");
        this.addDocumentTyp(type);

        Block block = new Block("^Dividendenabrechnung$");
        type.addBlock(block);
        Transaction<AccountTransaction> pdfTransaction = new Transaction<AccountTransaction>().subject(() -> {
            AccountTransaction entry = new AccountTransaction();
            entry.setType(AccountTransaction.Type.DIVIDENDS);
            return entry;
        });

        pdfTransaction
                // Nominal/Stück Siemens AG Namens-Aktien o.N.
                // ST 52 ISIN DE0007236101
                // Dividenden pro Stück  1,60000 EUR
                .section("name", "isin", "currency")
                .match("^Nominal\\/St.ck (?<name>.*)$")
                .match("^ST [\\.,\\d]+ ISIN (?<isin>[A-Z]{2}[A-Z0-9]{9}[0-9])$")
                .match("^Dividenden pro St.ck ([\\s])?[\\.,\\d]+ (?<currency>[\\w]{3})$")
                .assign((t, v) -> t.setSecurity(getOrCreateSecurity(v)))

                // ST 52 ISIN DE0007236101
                .section("shares")
                .match("^ST (?<shares>[\\.,\\d]+) ISIN (?<isin>[A-Z]{2}[A-Z0-9]{9}[0-9])$")
                .assign((t, v) -> t.setShares(asShares(v.get("shares"))))

                // Zahlungstag 27.01.2010  
                .section("date")
                .match("^Zahlungstag (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}).*$")
                .assign((t, v) -> t.setDateTime(asDate(v.get("date"))))

                // formatter:off
                // For dividend transactions, the gross amount is
                // calculated.
                //
                // The net amount is determined on the taxes and
                // withholding taxes.
                // formatter:on
                
                // Dividenden für 01.10.2008-30.09.2009 Bruttobetrag  83,20 EUR
                // Dividenden für 01.01.2009-31.12.2009 Bruttobetrag  163,08 USD  124,50 EUR
                .section("amount", "currency")
                .match("^.* Bruttobetrag .* (?<amount>[\\.,\\d]+) (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                    t.setAmount(asAmount(v.get("amount")));
                })

                // Verrechnungstopf Sonstige 0,  00 EUR Steuerbetrag -9,07 USD -6,93 EUR
                .section("tax", "currency").optional()
                .match("^.* Steuerbetrag .*\\-([\\s])?(?<tax>[\\.,\\d]+) (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    Money tax = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("tax")));

                    t.setMonetaryAmount(t.getMonetaryAmount().subtract(tax));
                })

                // Ausl. Quellensteuer -32,62 USD -24,90 EUR
                .section("tax", "currency").optional()
                .match("^Ausl\\. Quellensteuer .*\\-([\\s])?(?<tax>[\\.,\\d]+) (?<currency>[\\w]{3}).*$")
                .assign((t, v) -> {
                    Money tax = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("tax")));

                    t.setMonetaryAmount(t.getMonetaryAmount().subtract(tax));
                })

                // Dividenden für 01.01.2009-31.12.2009 Bruttobetrag  163,08 USD  124,50 EUR
                // Devisenkurs  1,267300
                .section("fxGross", "fxCurrency", "gross", "currency", "exchangeRate").optional()
                .match("^.* Bruttobetrag ([\\s])?(?<fxGross>[\\.,\\d]+) (?<fxCurrency>[\\w]{3}) ([\\s])?(?<gross>[\\.,\\d]+) (?<currency>[\\w]{3}).*$")
                .match("^Devisenkurs ([\\s])?(?<exchangeRate>[\\.,\\d]+).*$")
                .assign((t, v) -> {
                    v.put("baseCurrency", asCurrencyCode(v.get("currency")));
                    v.put("termCurrency", asCurrencyCode(v.get("fxCurrency")));

                    type.getCurrentContext().putType(asExchangeRate(v));

                    Money gross = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("gross")));
                    Money fxGross = Money.of(asCurrencyCode(v.get("fxCurrency")), asAmount(v.get("fxGross")));

                    checkAndSetGrossUnit(gross, fxGross, t, type);
                })

                .conclude(PDFExtractorUtils.fixGrossValueA())

                // Referenz DZ:255990
                .section("note").optional()
                .match("^(?<note>Referenz .*)$")
                .assign((t, v) -> t.setNote(trim(v.get("note"))))

                .wrap(t -> {
                    if (t.getCurrencyCode() != null && t.getAmount() != 0)
                        return new TransactionItem(t);
                    return null;
                });

        addTaxesSectionsTransaction(pdfTransaction, type);
        addFeesSectionsTransaction(pdfTransaction, type);

        block.set(pdfTransaction);
    }

    private void addDepotStatementTransaction()
    {
        DocumentType type = new DocumentType("Kontoauszug");
        this.addDocumentTyp(type);

        // @formatter:off
        // Kontoübertrag 1197537 28.05.2019 28.05.2019 3.000,00 EUR
        // Sammelgutschrift 19.12.2019 19.12.2019 5.000,00 EUR
        // Überweisungsgutschrift Inland 27.12.2019 27.12.2019 2.000,00 EUR
        // Interne Buchung 31.01.2020 31.01.2020 2,84 EUR
        // @formatter:on
        Block depositBlock = new Block("^(Konto.bertrag [\\d]+|"
                        + "Sammelgutschrift|"
                        + "Interne Buchung|"
                        + ".berweisungsgutschrift Inland) "
                        + ".* "
                        + "[\\.,\\d]+([\\s])?[\\w]{3}.*$");
        type.addBlock(depositBlock);
        depositBlock.setMaxSize(5);
        depositBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction t = new AccountTransaction();
                    t.setType(AccountTransaction.Type.DEPOSIT);
                    return t;
                })

                .section("note1", "note2", "date", "amount", "currency")
                .match("^(?<note1>(Konto.bertrag [\\d]+|"
                                + "Sammelgutschrift|"
                                + "Interne Buchung|"
                                + ".berweisungsgutschrift Inland)) "
                                + "[\\d]{2}\\.[\\d]{2}\\.[\\d]{4} "
                                + "(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d\\s]+) "
                                + "(?<amount>[\\.,\\d]+)([\\s])?(?<currency>[\\w]{3}).*$")
                .match("^(?<note2>Ref\\.: .*)$")
                .assign((t, v) -> {
                    t.setDateTime(asDate(stripBlanks(v.get("date"))));
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setNote(v.get("note1") + " " + trim(v.get("note2")));
                })

                .wrap(TransactionItem::new));

        // @formatter:off
        // Rücküberweisung Inland 23.12.2019 19.12.2019 -5.002,84 EUR
        // @formatter:on
        Block removalBlock = new Block("^R.cküberweisung Inland .* \\-[\\.,\\d]+([\\s])?[\\w]{3}.*$");
        type.addBlock(removalBlock);
        removalBlock.setMaxSize(5);
        removalBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction t = new AccountTransaction();
                    t.setType(AccountTransaction.Type.REMOVAL);
                    return t;
                })

                .section("note1", "note2", "date", "amount")
                .match("^(?<note1>R.cküberweisung Inland) [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} "
                                + "(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d\\s]+) "
                                + "\\-(?<amount>[\\.,\\d]+)([\\s])?(?<currency>[\\w]{3}).*$")
                .match("^(?<note2>Ref\\.: .*)$")
                .assign((t, v) -> {
                    t.setDateTime(asDate(stripBlanks(v.get("date"))));
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setNote(v.get("note1") + " " + trim(v.get("note2")));
                })

                .wrap(TransactionItem::new));

        // @formatter:off
        // Steueroptimierung 12.06.2020 12.06.2020 36,82 EUR
        // @formatter:on
        Block taxReturnBlock = new Block("^Steueroptimierung .* [\\.,\\d]+([\\s])?[\\w]{3}.*$");
        type.addBlock(taxReturnBlock);
        taxReturnBlock.setMaxSize(5);
        taxReturnBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction t = new AccountTransaction();
                    t.setType(AccountTransaction.Type.TAX_REFUND);
                    return t;
                })

                .section("note1", "note2", "date", "amount", "currency")
                .match("^(?<note1>Steueroptimierung) [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} "
                                + "(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d\\s]+) "
                                + "(?<amount>[\\.,\\d]+)([\\s])?(?<currency>[\\w]{3}).*$")
                .match("^(?<note2>Ref\\.: .*)$")
                .assign((t, v) -> {
                    t.setDateTime(asDate(stripBlanks(v.get("date"))));
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setNote(v.get("note1") + " " + trim(v.get("note2")));
                })

                .wrap(TransactionItem::new));

        // @formatter:off
        // Vermögensverwaltungshonorar 31.08.2019 31.08.2019 -5,75 EUR
        // Vermögensverwaltungshonorar 0000000000, 01.09.2019 - 30.09.2019 30.09.2019 30.09.2019 -6,98 EUR
        // @formatter:on
        Block feesBlock01 = new Block("^Verm.gensverwaltungshonorar.* \\-[\\.,\\d]+([\\s])?[\\w]{3}.*$");
        type.addBlock(feesBlock01);
        feesBlock01.setMaxSize(5);
        feesBlock01.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction t = new AccountTransaction();
                    t.setType(AccountTransaction.Type.FEES);
                    return t;
                })

                .section("note1", "date", "amount", "currency", "note2")
                .match("^(?<note1>Verm.gensverwaltungshonorar).* [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} (\\- )?"
                                + "(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d\\s]+) "
                                + "\\-(?<amount>[\\.,\\d]+)([\\s])?(?<currency>[\\w]{3}).*$")
                .match("^(?<note2>Ref\\.: .*)$")
                .assign((t, v) -> {
                    t.setDateTime(asDate(stripBlanks(v.get("date"))));
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setNote(v.get("note1") + " " + trim(v.get("note2")));
                })

                .wrap(TransactionItem::new));

        // @formatter:off
        // Flatrate, Ref: KA-0139816662 30.06.2010 30.06.2010 -75,00 EUR
        // Gebühren 01.06.2010 - 30.06.2010
        // @formatter:on
        Block feesBlock02 = new Block("^Flatrate, .* \\-[\\.,\\d]+ [\\w]{3}.*$");
        type.addBlock(feesBlock02);
        feesBlock02.setMaxSize(5);
        feesBlock02.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction t = new AccountTransaction();
                    t.setType(AccountTransaction.Type.FEES);
                    return t;
                })

                .section("note1", "date", "amount", "currency", "note2")
                .match("^(?<note1>Flatrate),( .*)? [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} (\\- )?"
                                + "(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d\\s]+) "
                                + "\\-(?<amount>[\\.,\\d]+)([\\s])?(?<currency>[\\w]{3})$")
                .match("^(?<note2>Geb.hren [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} \\- [\\d]{2}\\.[\\d]{2}\\.[\\d]{4}).*$")
                .assign((t, v) -> {
                    t.setDateTime(asDate(stripBlanks(v.get("date"))));
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setNote(v.get("note1") + " " + trim(v.get("note2")));
                })

                .wrap(TransactionItem::new));

        // @formatter:off
        // Volumen Fee, Ref: KA-0139816664 30.06.2010 30.06.2010 -29,55 EUR
        // Gebühren Depot Konto/Depot-Nr. 01.04.2010 - 30.06.2010
        // @formatter:on
        Block feesBlock03 = new Block("^Volumen Fee, .* \\-[\\.,\\d]+([\\s])?[\\w]{3}.*$");
        type.addBlock(feesBlock03);
        feesBlock03.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction t = new AccountTransaction();
                    t.setType(AccountTransaction.Type.FEES);
                    return t;
                })

                .section("note1", "date", "amount", "currency", "note2", "note3")
                .match("^(?<note1>Volumen Fee),( .*)? [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} (\\- )?"
                                + "(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d\\s]+) ([\\s])?"
                                + "\\-(?<amount>[\\.,\\d]+)([\\s])?(?<currency>[\\w]{3}).*$")
                .match("^(?<note2>Geb.hren).* (?<note3>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4} \\- [\\d]{2}\\.[\\d]{2}\\.[\\d]{4}).*$")
                .assign((t, v) -> {
                    t.setDateTime(asDate(stripBlanks(v.get("date"))));
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setNote(v.get("note1") + " " + trim(v.get("note2")) + " " + trim(v.get("note3")));
                })

                .wrap(TransactionItem::new));

        // @formatter:off
        // Haben-Zinsen Kontoabschluss, Ref: KA-0139907281 30.06.2010 30.06.2010 4,61EUR
        // @formatter:on
        Block interestBlock = new Block("^Haben\\-Zinsen Kontoabschluss, .* ([\\s])?[\\.,\\d]+([\\s])?[\\w]{3}.*$");
        type.addBlock(interestBlock);
        interestBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction t = new AccountTransaction();
                    t.setType(AccountTransaction.Type.INTEREST);
                    return t;
                })

                .section("note1", "note2", "date", "amount", "currency")
                .match("^(?<note1>Haben\\-Zinsen Kontoabschluss), "
                                + "(?<note2>.*) [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} (\\- )?"
                                + "(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d\\s]+) ([\\s])?"
                                + "(?<amount>[\\.,\\d]+)([\\s])?(?<currency>[\\w]{3}).*$")
                .assign((t, v) -> {
                    t.setDateTime(asDate(stripBlanks(v.get("date"))));
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setNote(v.get("note1") + " " + trim(v.get("note2")));
                })

                .wrap(TransactionItem::new));

        // @formatter:off
        // Steuerbuchung Abgeltungsteuer, Ref: H-0139925023 30.06.2010 30.06.2010 -1,28 EUR
        // Steuern auf Kontoabschluss
        // @formatter:on
        Block taxBlock = new Block("^Steuerbuchung Abgeltungsteuer, .* \\-[\\.,\\d]+([\\s])?[\\w]{3}.*$");
        type.addBlock(taxBlock);
        taxBlock.setMaxSize(5);
        taxBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction t = new AccountTransaction();
                    t.setType(AccountTransaction.Type.TAXES);
                    return t;
                })

                .section("note1", "note2", "date", "amount", "currency").optional()
                .match("^(?<note1>Steuerbuchung Abgeltungsteuer), (?<note2>.*) "
                                + "[\\d]{2}\\.[\\d]{2}\\.[\\d]{4} "
                                + "(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d\\s]+) ([\\s])?"
                                + "\\-(?<amount>[\\.,\\d]+)([\\s])?(?<currency>[\\w]{3}).*$")
                .match("^Steuern auf Kontoabschluss.*$")
                .assign((t, v) -> {
                    t.setDateTime(asDate(stripBlanks(v.get("date"))));
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setNote(v.get("note1") + " " + trim(v.get("note2")));
                })

                .wrap(t -> {
                    if (t.getCurrencyCode() != null && t.getAmount() != 0)
                        return new TransactionItem(t);
                    return null;
                }));

        // @formatter:off
        // Rückvergütung Bestandsprovision, Ref: KA-0144683460 30.07.2010 30.07.2010 2,03EUR
        //
        // Bestand LU0303756539
        // @formatter:on
        Block feeRefundBlock = new Block("^R.ckverg.tung Bestand.*, .* [\\.,\\d]+([\\s])?[\\w]{3}.*$");
        type.addBlock(feeRefundBlock);
        feeRefundBlock.setMaxSize(5);
        feeRefundBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction t = new AccountTransaction();
                    t.setType(AccountTransaction.Type.FEES_REFUND);
                    return t;
                })

                .section("note1", "note2", "date", "amount", "currency", "note3")
                .match("^R.ckverg.tung (?<note1>Bestand.*), (?<note2>.*) "
                                + "[\\d]{2}\\.[\\d]{2}\\.[\\d]{4} "
                                + "(?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d\\s]+) ([\\s])?"
                                + "(?<amount>[\\.,\\d]+)([\\s])?(?<currency>[\\w]{3}).*$")
                .match("^Bestand (?<note3>[A-Z]{2}[A-Z0-9]{9}[0-9]).*$")
                .assign((t, v) -> {
                    t.setDateTime(asDate(stripBlanks(v.get("date"))));
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setNote(v.get("note1") + " " + trim(v.get("note3")) + " " + trim(v.get("note2")));
                })

                .wrap(t -> {
                    if (t.getCurrencyCode() != null && t.getAmount() != 0)
                        return new TransactionItem(t);
                    return null;
                }));
    }

    private <T extends Transaction<?>> void addTaxesSectionsTransaction(T transaction, DocumentType type)
    {
        transaction
                // Ausl. Quellensteuer -32,62 USD -24,90 EUR
                .section("withHoldingTax", "currency").optional()
                .match("^Ausl\\. Quellensteuer .*\\-([\\s])?(?<withHoldingTax>[\\.,\\d]+) (?<currency>[\\w]{3}).*$")
                .assign((t, v) -> processWithHoldingTaxEntries(t, v, "withHoldingTax", type))

                // Kapitalertragsteuer EUR - 752,05
                // Kapitalertragsteuer EUR -73,71
                .section("currency", "tax").optional()
                .match("^Kapitalertragsteuer (?<currency>[\\w]{3}) \\-([\\s])?(?<tax>[\\.,\\d]+)$")
                .assign((t, v) -> processTaxEntries(t, v, type))

                // Solidaritätszuschlag EUR - 41,36
                // Solidaritätszuschlag EUR -4,05
                .section("currency", "tax").optional()
                .match("^Solidarit.tszuschlag (?<currency>[\\w]{3}) \\-([\\s])?(?<tax>[\\.,\\d]+)$")
                .assign((t, v) -> processTaxEntries(t, v, type))

                // Kirchensteuer EUR - 1,00
                // Kirchensteuer EUR -1,00
                .section("currency", "tax").optional()
                .match("^Kirchensteuer (?<currency>[\\w]{3}) \\-([\\s])?(?<tax>[\\.,\\d]+)$")
                .assign((t, v) -> processTaxEntries(t, v, type))

                // Verrechnungstopf Sonstige  0,00 EUR Steuerbetrag -144,33 EUR
                // Verrechnungstopf Sonstige 0,  00 EUR Steuerbetrag -9,07 USD -6,93 EUR
                .section("tax", "currency").optional()
                .match("^.* Steuerbetrag .*\\-([\\s])?(?<tax>[\\.,\\d]+) (?<currency>[\\w]{3})$")
                .assign((t, v) -> processTaxEntries(t, v, type));
    }

    private <T extends Transaction<?>> void addFeesSectionsTransaction(T transaction, DocumentType type)
    {
        transaction
                // Abwicklungsgebühren * EUR - 4,90
                .section("currency", "fee").optional()
                .match("^Abwicklungsgebühren \\* (?<currency>[\\w]{3}) \\-([\\s])?(?<fee>[\\.,'\\d]+)$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Courtage * EUR 0,00
                .section("currency", "fee").optional()
                .match("^Courtage \\* (?<currency>[\\w]{3}) \\-([\\s])?(?<fee>[\\.,'\\d]+)$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Spesen * EUR 0,00
                .section("currency", "fee").optional()
                .match("^Spesen \\* (?<currency>[\\w]{3}) \\-([\\s])?(?<fee>[\\.,'\\d]+)$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Bank-Provision EUR 0,00
                .section("currency", "fee").optional()
                .match("^Bank\\-Provision (?<currency>[\\w]{3}) \\-([\\s])?(?<fee>[\\.,'\\d]+)$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Verwahrart Girosammel-Verwahrung Spesen * -0,69 EUR
                // Verwahrart Wertpapierrechnung / Drittverw. Spesen * -6,50 EUR
                .section("fee", "currency").optional()
                .match("^Verwahrart .* Spesen \\* .*\\-([\\s])?(?<fee>[\\.,\\d]+) (?<currency>[\\w]{3})$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Lagerland CBL-Deutschland Abwickl.Gebühr * -0,04 EUR
                .section("fee", "currency").optional()
                .match("^Lagerland .* Abwickl\\.Geb.hr \\* .*\\-([\\s])?(?<fee>[\\.,\\d]+) (?<currency>[\\w]{3})$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Lagerland USA Aktien/Renten Spesen * -20,00 USD -15,78 EUR
                .section("fee", "currency").optional()
                .match("^Lagerland .* Spesen \\* .*\\-([\\s])?(?<fee>[\\.,\\d]+) (?<currency>[\\w]{3})$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Ausführungsplatz Xetra Courtage * -0,08 EUR
                // Ausführungsplatz Fondshandel außerbörslich(FFF) Courtage *  0,00 EUR
                // Ausführungsplatz Stuttgart Courtage * -3,39 EUR
                .section("fee", "currency").optional()
                .match("^.* .* Courtage \\* .*\\-([\\s])?(?<fee>[\\.,\\d]+) (?<currency>[\\w]{3})$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Handelstag/-zeit 10.02.2010   17:08:23 Bank-Provision -0,02 EUR
                .section("fee", "currency").optional()
                .match("^.* Bank\\-Provision .*\\-([\\s])?(?<fee>[\\.,\\d]+) (?<currency>[\\w]{3})$")
                .assign((t, v) -> processFeeEntries(t, v, type));
    }
}
