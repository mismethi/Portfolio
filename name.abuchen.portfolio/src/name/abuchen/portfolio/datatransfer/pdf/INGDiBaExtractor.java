package name.abuchen.portfolio.datatransfer.pdf;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import name.abuchen.portfolio.datatransfer.pdf.PDFParser.Block;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.DocumentType;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.Transaction;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.Money;

public class INGDiBaExtractor extends AbstractPDFExtractor
{
    private static final String IS_JOINT_ACCOUNT = "isjointaccount"; //$NON-NLS-1$
    private static final String EXCHANGE_RATE = "exchangeRate"; //$NON-NLS-1$

    BiConsumer<Map<String, String>, String[]> isJointAccount = (context, lines) -> {
        Pattern pJointAccount = Pattern.compile("KapSt anteilig 50,00 %.*"); //$NON-NLS-1$
        Boolean bJointAccount = false;
        for (String line : lines)
        {
            Matcher m = pJointAccount.matcher(line);
            if (m.matches())
            {
                context.put(IS_JOINT_ACCOUNT, Boolean.TRUE.toString());
                bJointAccount = true;
                break;
            }
        }

        if (!bJointAccount)
            context.put(IS_JOINT_ACCOUNT, Boolean.FALSE.toString());

    };

    public INGDiBaExtractor(Client client)
    {
        super(client);

        addBuyTransaction();
        addSellTransaction();
        addErtragsgutschrift();
        addZinsgutschrift();
        addDividendengutschrift();
        addAdvanceFeeTransaction();
    }

    @Override
    public String getPDFAuthor()
    {
        return "ING-DiBa"; //$NON-NLS-1$
    }

    @Override
    public String getLabel()
    {
        return "ING-DiBa"; //$NON-NLS-1$
    }

    @SuppressWarnings("nls")
    private void addBuyTransaction()
    {
        DocumentType type = new DocumentType("Wertpapierabrechnung (Kauf|Bezug).*");
        this.addDocumentTyp(type);

        Block block = new Block("Wertpapierabrechnung (Kauf|Bezug).*");
        type.addBlock(block);
        block.set(new Transaction<BuySellEntry>()

                        .subject(() -> {
                            BuySellEntry entry = new BuySellEntry();
                            entry.setType(PortfolioTransaction.Type.BUY);
                            return entry;
                        })

                        .section("wkn", "isin", "name", "name1") //
                        .match("^ISIN \\(WKN\\) (?<isin>[^ ]*) \\((?<wkn>.*)\\)$")
                        .match("Wertpapierbezeichnung (?<name>.*)").match("(?<name1>.*)")
                        .assign((t, v) -> {
                            if (!v.get("name1").startsWith("Nominale"))
                                v.put("name", v.get("name") + " " + v.get("name1"));
                            t.setSecurity(getOrCreateSecurity(v));
                        })

                        .section("shares")
                        .match("^Nominale( St.ck)? (?<shares>[\\d.]+(,\\d+)?).*")
                        .assign((t, v) -> t.setShares(asShares(v.get("shares"))))

                        .section("time").optional() //
                        .match("(Ausf.hrungstag . -zeit|Ausf.hrungstag|Schlusstag . -zeit|Schlusstag) .* (?<time>\\d+:\\d+:\\d+).*") //
                        .assign((t, v) -> {
                            type.getCurrentContext().put("time", v.get("time"));
                        })

                        .section("date") //
                        .match("(Ausf.hrungstag . -zeit|Ausf.hrungstag|Schlusstag . -zeit|Schlusstag) (?<date>\\d+.\\d+.\\d{4}).*") //
                        .assign((t, v) -> {
                            if (type.getCurrentContext().get("time") != null)
                            {
                                t.setDate(asDate(v.get("date"), type.getCurrentContext().get("time")));
                            }
                            else
                            {
                                t.setDate(asDate(v.get("date")));
                            }
                        })

                        .section("amount", "currency") //
                        .match("Endbetrag zu Ihren Lasten (?<currency>\\w{3}) (?<amount>[\\d.]+,\\d+)") //
                        .assign((t, v) -> {
                            t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                            t.setAmount(asAmount(v.get("amount")));
                        })

                        .section("fee", "currency").optional() //
                        .match("Handelsplatzgeb.hr (?<currency>\\w{3}) (?<fee>[\\d.]+,\\d+)") //
                        .assign((t, v) -> t.getPortfolioTransaction()
                                        .addUnit(new Unit(Unit.Type.FEE,
                                                        Money.of(asCurrencyCode(v.get("currency")),
                                                                        asAmount(v.get("fee"))))))

                        .section("fee", "currency").optional() //
                        .match("Provision (?<currency>\\w{3}) (?<fee>[\\d.]+,\\d+)") //
                        .assign((t, v) -> t.getPortfolioTransaction()
                                        .addUnit(new Unit(Unit.Type.FEE,
                                                        Money.of(asCurrencyCode(v.get("currency")),
                                                                        asAmount(v.get("fee"))))))

                        .section("fee", "currency").optional() //
                        .match("Handelsentgelt (?<currency>\\w{3}) (?<fee>[\\d.]+,\\d+)") //
                        .assign((t, v) -> t.getPortfolioTransaction()
                                        .addUnit(new Unit(Unit.Type.FEE,
                                                        Money.of(asCurrencyCode(v.get("currency")),
                                                                        asAmount(v.get("fee"))))))

                        .wrap(t -> {
                            if (t.getPortfolioTransaction().getDateTime() == null)
                                throw new IllegalArgumentException("Missing date");
                            return new BuySellEntryItem(t);
                        }));
    }

    @SuppressWarnings("nls")
    private void addSellTransaction()
    {
        DocumentType type = new DocumentType("Wertpapierabrechnung Verkauf", isJointAccount);
        this.addDocumentTyp(type);

        Block block = new Block("Wertpapierabrechnung Verkauf.*");
        type.addBlock(block);
        Transaction<BuySellEntry> transaction = new Transaction<BuySellEntry>()

                        .subject(() -> {
                            BuySellEntry entry = new BuySellEntry();
                            entry.setType(PortfolioTransaction.Type.SELL);
                            return entry;
                        })
                        
                        .section("wkn", "isin", "name", "name1") //
                        .match("^ISIN \\(WKN\\) (?<isin>[^ ]*) \\((?<wkn>.*)\\)$")
                        .match("Wertpapierbezeichnung (?<name>.*)")
                        .match("(?<name1>.*)")
                        .assign((t, v) -> {
                            if (!v.get("name1").startsWith("Nominale"))
                                v.put("name", v.get("name") + " " + v.get("name1"));
                            t.setSecurity(getOrCreateSecurity(v));
                        })

                        .section("shares")
                        .match("^Nominale St.ck (?<shares>[\\d.]+(,\\d+)?)")
                        .assign((t, v) -> t.setShares(asShares(v.get("shares"))))

                        .section("date") //
                        .match("(Ausf.hrungstag . -zeit|Ausf.hrungstag|Schlusstag . -zeit|Schlusstag) (?<date>\\d+.\\d+.\\d{4}).*") //
                        .assign((t, v) -> t.setDate(asDate(v.get("date"))))

                        .section("date", "time").optional() //
                        .match("(Ausf.hrungstag . -zeit|Ausf.hrungstag|Schlusstag . -zeit|Schlusstag) (?<date>\\d+.\\d+.\\d{4}) .* (?<time>\\d+:\\d+:\\d+).*") //
                        .assign((t, v) -> t.setDate(asDate(v.get("date"), v.get("time"))))

                        .section("amount", "currency") //
                        .match("Endbetrag zu Ihren Gunsten (?<currency>\\w{3}) (?<amount>[\\d.]+,\\d+)") //
                        .assign((t, v) -> {
                            t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                            t.setAmount(asAmount(v.get("amount")));
                        })

                        .section("fee", "currency").optional() //
                        .match("Handelsplatzgeb.hr (?<currency>\\w{3}) (?<fee>[\\d.]+,\\d+)") //
                        .assign((t, v) -> t.getPortfolioTransaction()
                                        .addUnit(new Unit(Unit.Type.FEE,
                                                        Money.of(asCurrencyCode(v.get("currency")),
                                                                        asAmount(v.get("fee"))))))

                        .section("fee", "currency").optional() //
                        .match("Provision (?<currency>\\w{3}) (?<fee>[\\d.]+,\\d+)") //
                        .assign((t, v) -> t.getPortfolioTransaction()
                                        .addUnit(new Unit(Unit.Type.FEE,
                                                        Money.of(asCurrencyCode(v.get("currency")),
                                                                        asAmount(v.get("fee"))))))

                        .section("fee", "currency").optional() //
                        .match("Handelsentgelt (?<currency>\\w{3}) (?<fee>[\\d.]+,\\d+)") //
                        .assign((t, v) -> t.getPortfolioTransaction()
                                        .addUnit(new Unit(Unit.Type.FEE,
                                                        Money.of(asCurrencyCode(v.get("currency")),
                                                                        asAmount(v.get("fee"))))))

                        .wrap(BuySellEntryItem::new);

        addTaxSectionToBuySellEntry(type, transaction);
        block.set(transaction);
    }

    @SuppressWarnings("nls")
    private void addErtragsgutschrift()
    {
        DocumentType type = new DocumentType("Ertragsgutschrift", isJointAccount);
        this.addDocumentTyp(type);

        Block block = new Block("Ertragsgutschrift.*");
        type.addBlock(block);
        Transaction<AccountTransaction> transaction = new Transaction<AccountTransaction>()

                        .subject(() -> {
                            AccountTransaction entry = new AccountTransaction();
                            entry.setType(AccountTransaction.Type.DIVIDENDS);
                            return entry;
                        })

                        .section("wkn", "isin", "name")
                        .match("^ISIN \\(WKN\\) (?<isin>[^ ]*) \\((?<wkn>.*)\\)$")
                        .match("Wertpapierbezeichnung (?<name>.*)")
                        .assign((t, v) -> t.setSecurity(getOrCreateSecurity(v)))

                        .section("shares")
                        .match("^Nominale (?<shares>[\\d.]+(,\\d+)?) .*")
                        .assign((t, v) -> t.setShares(asShares(v.get("shares"))))

                        .section("date") //
                        .match("Zahltag (?<date>\\d+.\\d+.\\d{4})") //
                        .assign((t, v) -> t.setDateTime(asDate(v.get("date"))))

                        .section("currency") //
                        .match("Gesamtbetrag zu Ihren (Gunsten|Lasten) (?<currency>\\w{3}) .*") //
                        .assign((t, v) -> t.setCurrencyCode(asCurrencyCode(v.get("currency"))));

        // make sure that tax elements are parsed *before* the total amount so
        // that we can convert to a TAX transaction if necessary
        addTaxSectionToAccountTransaction(type, transaction);

        transaction.section("amount") //
                        .match("Gesamtbetrag zu Ihren (Gunsten|Lasten) \\w{3} (?<amount>(- )?[\\d.]+,\\d+)") //
                        .assign((t, v) -> {
                            if (v.get("amount").startsWith("-"))
                            {
                                // create a tax transaction as the amount is
                                // negative

                                Money amount = t.getUnitSum(Unit.Type.TAX);

                                t.setType(AccountTransaction.Type.TAXES);
                                t.clearUnits();
                                t.setMonetaryAmount(amount);
                            }
                            else
                            {
                                t.setAmount(asAmount(v.get("amount")));
                            }
                        })

                        .wrap(TransactionItem::new);

        block.set(transaction);
    }

    @SuppressWarnings("nls")
    private void addZinsgutschrift()
    {
        DocumentType type = new DocumentType("Zinsgutschrift", isJointAccount);
        this.addDocumentTyp(type);

        Block block = new Block("Zinsgutschrift.*");
        type.addBlock(block);
        Transaction<AccountTransaction> transaction = new Transaction<AccountTransaction>()

                        .subject(() -> {
                            AccountTransaction entry = new AccountTransaction();
                            entry.setType(AccountTransaction.Type.DIVIDENDS);
                            return entry;
                        })

                        .section("wkn", "isin", "name")
                        .match("^ISIN \\(WKN\\) (?<isin>[^ ]*) \\((?<wkn>.*)\\)$")
                        .match("Wertpapierbezeichnung (?<name>.*)")
                        .assign((t, v) -> t.setSecurity(getOrCreateSecurity(v)))

                        .section("date") //
                        .match("Zahltag (?<date>\\d+.\\d+.\\d{4})") //
                        .assign((t, v) -> t.setDateTime(asDate(v.get("date"))))

                        .section("amount", "currency") //
                        .match("Gesamtbetrag zu Ihren Gunsten (?<currency>\\w{3}) (?<amount>[\\d.]+,\\d+)") //
                        .assign((t, v) -> {
                            t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                            t.setAmount(asAmount(v.get("amount")));
                        })

                        .wrap(TransactionItem::new);

        addTaxSectionToAccountTransaction(type, transaction);
        block.set(transaction);
    }

    @SuppressWarnings("nls")
    private void addDividendengutschrift()
    {
        final DocumentType type = new DocumentType("Dividendengutschrift", isJointAccount);
        this.addDocumentTyp(type);

        Block block = new Block("Dividendengutschrift.*");
        type.addBlock(block);
        Transaction<AccountTransaction> transaction = new Transaction<AccountTransaction>()

                        .subject(() -> {
                            AccountTransaction entry = new AccountTransaction();
                            entry.setType(AccountTransaction.Type.DIVIDENDS);
                            return entry;
                        })

                        .section("wkn", "isin", "name", "name1") //
                        .match("^ISIN \\(WKN\\) (?<isin>[^ ]*) \\((?<wkn>.*)\\)$")
                        .match("Wertpapierbezeichnung (?<name>.*)")
                        .match("(?<name1>.*)")
                        .assign((t, v) -> {
                            if (!v.get("name1").startsWith("Nominale"))
                                v.put("name", v.get("name") + " " + v.get("name1"));
                            t.setSecurity(getOrCreateSecurity(v));
                        })

                        .section("shares") //
                        .match("^Nominale (?<shares>[\\d.]+(,\\d+)?) .*")
                        .assign((t, v) -> t.setShares(asShares(v.get("shares"))))

                        .section("amount", "currency") //
                        .match("Gesamtbetrag zu Ihren Gunsten (?<currency>\\w{3}) (?<amount>[\\d.]+,\\d+)") //
                        .assign((t, v) -> {
                            t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                            t.setAmount(asAmount(v.get("amount")));
                        })

                        .section("fxAmount", "fxCurrency", "currency", "exchangeRate").optional() //
                        .match("Brutto (?<fxCurrency>\\w{3}) (?<fxAmount>[\\d\\.]+,\\d+)") //
                        .match("Umg. z. Dev.-Kurs \\((?<exchangeRate>[\\d\\.]+,\\d+)\\) (?<currency>\\w{3}) ([\\d\\.]+,\\d+)") //
                        .assign((t, v) -> {
                            BigDecimal exchangeRate = asExchangeRate(v.get("exchangeRate"));
                            type.getCurrentContext().put(EXCHANGE_RATE, exchangeRate.toPlainString());

                            if (!t.getCurrencyCode().equals(t.getSecurity().getCurrencyCode()))
                            {
                                BigDecimal inverseRate = BigDecimal.ONE.divide(exchangeRate, 10,
                                                RoundingMode.HALF_DOWN);

                                Unit grossValue;
                                Money fxAmount = Money.of(asCurrencyCode(v.get("fxCurrency")),
                                                asAmount(v.get("fxAmount")));
                                Money amount = Money.of(asCurrencyCode(v.get("currency")),
                                                BigDecimal.valueOf(fxAmount.getAmount()).multiply(inverseRate)
                                                                .setScale(0, RoundingMode.HALF_UP).longValue());
                                grossValue = new Unit(Unit.Type.GROSS_VALUE, amount, fxAmount, inverseRate);
                                t.addUnit(grossValue);
                            }
                        })

                        .section("date") //
                        .match("Valuta (?<date>\\d+.\\d+.\\d{4})") //
                        .assign((t, v) -> t.setDateTime(asDate(v.get("date"))))

                        .wrap(TransactionItem::new);

        addTaxSectionToAccountTransaction(type, transaction);
        block.set(transaction);
    }

    @SuppressWarnings("nls")
    private void addTaxSectionToBuySellEntry(DocumentType type, Transaction<BuySellEntry> transaction)
    {
        transaction
                        // Kapitalerstragsteuer (Einzelkonto)
                        .section("tax", "currency").optional() //
                        .match("Kapitalertragsteuer \\d+,\\d+ ?% (?<currency>\\w{3}) (?<tax>[\\d.]+,\\d+)")
                        .assign((t, v) -> t.getPortfolioTransaction()
                                        .addUnit(new Unit(Unit.Type.TAX,
                                                        Money.of(asCurrencyCode(v.get("currency")),
                                                                        asAmount(v.get("tax"))))))

                        // Kapitalerstragsteuer (Gemeinschaftskonto)
                        .section("tax1", "currency1", "tax2", "currency2").optional() //
                        .match("KapSt anteilig 50,00 ?% \\d+,\\d+ ?% (?<currency1>\\w{3}) (?<tax1>[\\d.]+,\\d+)")
                        .match("KapSt anteilig 50,00 ?% \\d+,\\d+ ?% (?<currency2>\\w{3}) (?<tax2>[\\d.]+,\\d+)")
                        .assign((t, v) -> {
                            t.getPortfolioTransaction().addUnit(new Unit(Unit.Type.TAX,
                                            Money.of(asCurrencyCode(v.get("currency1")), asAmount(v.get("tax1")))));
                            t.getPortfolioTransaction().addUnit(new Unit(Unit.Type.TAX,
                                            Money.of(asCurrencyCode(v.get("currency2")), asAmount(v.get("tax2")))));
                        })

                        // Solidarit??tszuschlag (ein Eintrag bei Einzelkonto)
                        .section("tax", "currency").optional() //
                        .match("Solidarit.tszuschlag \\d+,\\d+ ?% (?<currency>\\w{3}) (?<tax>[\\d.]+,\\d+)")
                        .assign((t, v) -> {
                            if (!Boolean.parseBoolean(type.getCurrentContext().get(IS_JOINT_ACCOUNT)))
                                t.getPortfolioTransaction().addUnit(new Unit(Unit.Type.TAX,
                                                Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("tax")))));
                        })

                        // Solidarit??tszuschlag (zwei Eintr??ge bei
                        // Gemeinschaftskonto)
                        .section("tax1", "currency1", "tax2", "currency2").optional() //
                        .match("Solidarit.tszuschlag \\d+,\\d+ ?% (?<currency1>\\w{3}) (?<tax1>[\\d.]+,\\d+)")
                        .match("Solidarit.tszuschlag \\d+,\\d+ ?% (?<currency2>\\w{3}) (?<tax2>[\\d.]+,\\d+)")
                        .assign((t, v) -> {
                            t.getPortfolioTransaction().addUnit(new Unit(Unit.Type.TAX,
                                            Money.of(asCurrencyCode(v.get("currency1")), asAmount(v.get("tax1")))));
                            t.getPortfolioTransaction().addUnit(new Unit(Unit.Type.TAX,
                                            Money.of(asCurrencyCode(v.get("currency2")), asAmount(v.get("tax2")))));
                        })

                        // Kirchensteuer (ein Eintrag bei Einzelkonto)
                        .section("tax", "currency").optional() //
                        .match("Kirchensteuer \\d+,\\d+ ?% (?<currency>\\w{3}) (?<tax>[\\d.]+,\\d+)")
                        .assign((t, v) -> {
                            if (!Boolean.parseBoolean(type.getCurrentContext().get(IS_JOINT_ACCOUNT)))
                                t.getPortfolioTransaction().addUnit(new Unit(Unit.Type.TAX,
                                                Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("tax")))));
                        })

                        // Kirchensteuer (zwei Eintr??ge bei Gemeinschaftskonten)
                        .section("tax1", "currency1", "tax2", "currency2").optional() //
                        .match("Kirchensteuer \\d+,\\d+ ?% (?<currency1>\\w{3}) (?<tax1>[\\d.]+,\\d+)")
                        .match("Kirchensteuer \\d+,\\d+ ?% (?<currency2>\\w{3}) (?<tax2>[\\d.]+,\\d+)")
                        .assign((t, v) -> {
                            t.getPortfolioTransaction().addUnit(new Unit(Unit.Type.TAX,
                                            Money.of(asCurrencyCode(v.get("currency1")), asAmount(v.get("tax1")))));
                            t.getPortfolioTransaction().addUnit(new Unit(Unit.Type.TAX,
                                            Money.of(asCurrencyCode(v.get("currency2")), asAmount(v.get("tax2")))));
                        });

    }

    @SuppressWarnings("nls")
    private void addTaxSectionToAccountTransaction(DocumentType type, Transaction<AccountTransaction> transaction)
    {
        transaction
                        // Kapitalerstragsteuer (Einzelkonto)
                        .section("tax", "currency").optional() //
                        .match("Kapitalertragsteuer \\d+,\\d+ ?% (?<currency>\\w{3}) (?<tax>[\\d.]+,\\d+)")
                        .assign((t, v) -> t.addUnit(new Unit(Unit.Type.TAX,
                                        Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("tax"))))))

                        // Kapitalerstragsteuer (Gemeinschaftskonto)
                        .section("tax1", "currency1", "tax2", "currency2").optional() //
                        .match("KapSt anteilig 50,00 ?% \\d+,\\d+ ?% (?<currency1>\\w{3}) (?<tax1>[\\d.]+,\\d+)")
                        .match("KapSt anteilig 50,00 ?% \\d+,\\d+ ?% (?<currency2>\\w{3}) (?<tax2>[\\d.]+,\\d+)")
                        .assign((t, v) -> {
                            t.addUnit(new Unit(Unit.Type.TAX,
                                            Money.of(asCurrencyCode(v.get("currency1")), asAmount(v.get("tax1")))));
                            t.addUnit(new Unit(Unit.Type.TAX,
                                            Money.of(asCurrencyCode(v.get("currency2")), asAmount(v.get("tax2")))));
                        })

                        // Solidarit??tszuschlag (ein Eintrag bei Einzelkonto)
                        .section("tax", "currency").optional() //
                        .match("Solidarit.tszuschlag \\d+,\\d+ ?% (?<currency>\\w{3}) (?<tax>[\\d.]+,\\d+)")
                        .assign((t, v) -> {
                            if (!Boolean.parseBoolean(type.getCurrentContext().get(IS_JOINT_ACCOUNT)))
                                t.addUnit(new Unit(Unit.Type.TAX,
                                                Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("tax")))));
                        })

                        // Solidarit??tszuschlag (zwei Eintr??ge bei
                        // Gemeinschaftskonto)
                        .section("tax1", "currency1", "tax2", "currency2").optional() //
                        .match("Solidarit.tszuschlag \\d+,\\d+ ?% (?<currency1>\\w{3}) (?<tax1>[\\d.]+,\\d+)")
                        .match("Solidarit.tszuschlag \\d+,\\d+ ?% (?<currency2>\\w{3}) (?<tax2>[\\d.]+,\\d+)")
                        .assign((t, v) -> {
                            t.addUnit(new Unit(Unit.Type.TAX,
                                            Money.of(asCurrencyCode(v.get("currency1")), asAmount(v.get("tax1")))));
                            t.addUnit(new Unit(Unit.Type.TAX,
                                            Money.of(asCurrencyCode(v.get("currency2")), asAmount(v.get("tax2")))));
                        })

                        // Kirchensteuer (ein Eintrag bei Einzelkonto)
                        .section("tax", "currency").optional() //
                        .match("Kirchensteuer \\d+,\\d+ ?% (?<currency>\\w{3}) (?<tax>[\\d.]+,\\d+)")
                        .assign((t, v) -> {
                            if (!Boolean.parseBoolean(type.getCurrentContext().get(IS_JOINT_ACCOUNT)))
                                t.addUnit(new Unit(Unit.Type.TAX,
                                                Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("tax")))));
                        })

                        // Kirchensteuer (zwei Eintr??ge bei Gemeinschaftskonten)
                        .section("tax1", "currency1", "tax2", "currency2").optional() //
                        .match("Kirchensteuer \\d+,\\d+ ?% (?<currency1>\\w{3}) (?<tax1>[\\d.]+,\\d+)")
                        .match("Kirchensteuer \\d+,\\d+ ?% (?<currency2>\\w{3}) (?<tax2>[\\d.]+,\\d+)")
                        .assign((t, v) -> {
                            t.addUnit(new Unit(Unit.Type.TAX,
                                            Money.of(asCurrencyCode(v.get("currency1")), asAmount(v.get("tax1")))));
                            t.addUnit(new Unit(Unit.Type.TAX,
                                            Money.of(asCurrencyCode(v.get("currency2")), asAmount(v.get("tax2")))));
                        })

                        // Quellensteuer
                        .section("tax", "currency", "taxTx", "currencyTx").optional() //
                        .match("QuSt \\d+,\\d+ % \\((?<currency>\\w{3}) (?<tax>[\\d.,]*)\\) (?<currencyTx>\\w{3}) (?<taxTx>[\\d.,]*)")
                        .assign((t, v) -> {
                            String currency = asCurrencyCode(v.get("currency"));
                            String currencyTx = asCurrencyCode(v.get("currencyTx"));

                            if (currency.equals(t.getCurrencyCode()))
                                t.addUnit(new Unit(Unit.Type.TAX, Money.of(currency, asAmount(v.get("tax")))));
                            else if (type.getCurrentContext().containsKey(EXCHANGE_RATE))
                            {
                                BigDecimal exchangeRate = new BigDecimal(type.getCurrentContext().get(EXCHANGE_RATE));
                                BigDecimal inverseRate = BigDecimal.ONE.divide(exchangeRate, 10,
                                                RoundingMode.HALF_DOWN);
                                t.addUnit(new Unit(Unit.Type.TAX, Money.of(currencyTx, asAmount(v.get("taxTx"))),
                                                Money.of(currency, asAmount(v.get("tax"))), inverseRate));
                            }
                            else
                            {
                                t.addUnit(new Unit(Unit.Type.TAX, Money.of(currencyTx, asAmount(v.get("taxTx")))));
                            }
                        })

                        // Quellensteuer ohne Fremdw??hrung
                        .section("tax", "currency").optional() //
                        .match("QuSt \\d+,\\d+ % (?<currency>\\w{3}) (?<tax>[\\d.,]*)").assign((t, v) -> {
                            String currency = asCurrencyCode(v.get("currency"));
                            t.addUnit(new Unit(Unit.Type.TAX, Money.of(currency, asAmount(v.get("tax")))));
                        });
    }

    @SuppressWarnings("nls")
    private void addAdvanceFeeTransaction()
    {
        DocumentType type = new DocumentType("Vorabpauschale");
        this.addDocumentTyp(type);

        Transaction<AccountTransaction> pdfTransaction = new Transaction<>();

        Block firstRelevantLine = new Block("Vorabpauschale");
        type.addBlock(firstRelevantLine);
        firstRelevantLine.set(pdfTransaction);

        pdfTransaction
                    .subject(() -> {
                        AccountTransaction t = new AccountTransaction();
                        t.setType(AccountTransaction.Type.TAXES);
                        return t;
                    })

                    // ISIN (WKN) IE00BKPT2S34 (A2P1KU)
                    // Wertpapierbezeichnung iShsIII-Gl.Infl.L.Gov.Bd U.ETF
                    // Reg. Shs HGD EUR Acc. oN
                    // Nominale 378,00 St??ck
                    .section("wkn", "isin", "name", "name1")
                    .match("^ISIN \\(WKN\\) (?<isin>[^ ]*) \\((?<wkn>.*)\\)$")
                    .match("^Wertpapierbezeichnung (?<name>.*)")
                    .match("(?<name1>.*)")
                    .assign((t, v) -> {
                        if (!v.get("name1").startsWith("Nominale"))
                            v.put("name", v.get("name") + " " + v.get("name1"));
                        t.setSecurity(getOrCreateSecurity(v));
                    })

                    // Nominale 378,00 St??ck
                    .section("shares")
                    .match("^Nominale (?<shares>[\\d.]+(,\\d+)?) .*")
                    .assign((t, v) -> t.setShares(asShares(v.get("shares"))))

                    // Ex-Tag 04.01.2021
                    .section("date")
                    .match("^Ex-Tag (?<date>\\d+.\\d+.\\d{4})")
                    .assign((t, v) -> t.setDateTime(asDate(v.get("date"))))

                    // Gesamtbetrag zu Ihren Lasten EUR - 0,16
                    .section("currency", "tax", "sign").optional()
                    .match("^Gesamtbetrag zu Ihren Lasten (?<currency>[\\w]{3}) (?<sign>[-\\s]*)?(?<tax>[.,\\d]*)")
                    .assign((t, v) -> {
                        t.setAmount(asAmount(v.get("tax")));
                        t.setCurrencyCode(asCurrencyCode(v.get("currency")));

                        String sign = v.get("sign").trim();
                        if ("".equals(sign))
                        {
                            // change type for withdrawals
                            t.setType(AccountTransaction.Type.TAX_REFUND);
                        }
                    })

                    .wrap(t -> {
                        if (t.getCurrencyCode() != null && t.getAmount() != 0)
                            return new TransactionItem(t);
                        return null;
                    });
    }
}
