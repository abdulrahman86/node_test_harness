package org.aion.harness.tests.integ;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.spec.InvalidKeySpecException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.aion.api.IAionAPI;
import org.aion.api.impl.AionAPIImpl;
import org.aion.api.type.ApiMsg;
import org.aion.api.type.BlockDetails;
import org.aion.harness.kernel.PrivateKey;
import org.aion.harness.kernel.RawTransaction;
import org.aion.harness.main.LocalNode;
import org.aion.harness.main.Network;
import org.aion.harness.main.NodeConfigurations;
import org.aion.harness.main.NodeConfigurations.DatabaseOption;
import org.aion.harness.main.NodeFactory;
import org.aion.harness.main.NodeFactory.NodeType;
import org.aion.harness.main.NodeListener;
import org.aion.harness.main.RPC;
import org.aion.harness.main.event.IEvent;
import org.aion.harness.main.event.PrepackagedLogEvents;
import org.aion.harness.main.types.ReceiptHash;
import org.aion.harness.main.types.TransactionReceipt;
import org.aion.harness.result.FutureResult;
import org.aion.harness.result.LogEventResult;
import org.aion.harness.result.Result;
import org.aion.harness.result.RpcResult;
import org.aion.harness.result.TransactionResult;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class JavaApiSmokeTest {
    private static final String BUILT_KERNEL = System.getProperty("user.dir") + "/aion";
    private static final String PREMINED_KEY = "4c3c8a7c0292bc55d97c50b4bdabfd47547757d9e5c194e89f66f25855baacd0";
    private static final long ENERGY_LIMIT = 1_234_567L;
    private static final long ENERGY_PRICE = 10_010_020_345L;

    private static LocalNode node;
    private static RPC rpc;
    private static NodeListener listener;
    private static PrivateKey preminedPrivateKey;

    @BeforeClass
    public static void setup() throws IOException, InterruptedException, DecoderException, InvalidKeySpecException {
        preminedPrivateKey = PrivateKey.fromBytes(Hex.decodeHex(PREMINED_KEY));

        NodeConfigurations configurations = NodeConfigurations.alwaysUseBuiltKernel(Network.CUSTOM, BUILT_KERNEL, DatabaseOption.PRESERVE_DATABASE);

        node = NodeFactory.getNewLocalNodeInstance(NodeType.JAVA_NODE);
        node.configure(configurations);
        Result result = node.initialize();
        System.out.println(result);
        assertTrue(result.isSuccess());
        assertTrue(node.start().isSuccess());
        assertTrue(node.isAlive());

        rpc = new RPC("127.0.0.1", "8545");
        listener = NodeListener.listenTo(node);
    }

    @AfterClass
    public static void tearDown() throws IOException, InterruptedException {
        assertTrue(node.stop().isSuccess());
        assertFalse(node.isAlive());
        node = null;
        rpc = null;
        listener = null;
        destroyLogs();

        // If we close and reopen the DB too quickly we get an error... this sleep tries to avoid
        // this issue so that the DB lock is released in time.
        Thread.sleep(TimeUnit.SECONDS.toMillis(10));
    }

    @Test(timeout = 300_000 /* millis */)
    public void testGetBlockDetailsByRange() throws Exception {
        IAionAPI api = AionAPIImpl.inst();
        ApiMsg connectionMsg = api.connect("tcp://localhost:8547");
        int tries = 0;

        while(connectionMsg.isError() && tries++ <= 10) {
            System.out.println("trying again after 3 sec");
            connectionMsg = api.connect("tcp://localhost:8547");
        }
        if(connectionMsg.isError()) {
            throw new RuntimeException("error: aion_api can't connect to kernel (after retrying 10 times).");
        }

        // send a transaction that deploys a simple contract and loads it with some funds
        System.out.println("Sending a transaction");
        BigInteger amount = BigInteger.TEN.pow(13).add(BigInteger.valueOf(2_938_652));
        RawTransaction transaction = buildTransactionToCreateAndTransferToFvmContract(amount);
        TransactionReceipt createReceipt = sendTransaction(transaction);
        assertTrue(createReceipt.getAddressOfDeployedContract().isPresent());

        final long b0 = createReceipt.getBlockNumber().longValue();
        long bn = b0;

        // wait for two more blocks
        while(bn < b0 + 2) {
            System.err.println("current block number = " + bn + "; waiting to reach block number " + (b0 + 2));
            TimeUnit.SECONDS.sleep(10); // expected block time
            bn = rpc.blockNumber().getResult();
        }

        System.out.println(String.format("Calling getBlockDetailsByRange(%d, %d)", b0, bn));
        ApiMsg blockDetailsMsg = api.getAdmin().getBlockDetailsByRange(b0, bn);
        assertThat(blockDetailsMsg.isError(), is(false));

        List<BlockDetails> blockDetails = blockDetailsMsg.getObject();
        assertThat("incorrect number of blocks",
            blockDetails.size(), is(3));
        assertThat("block details has incorrect block number",
            blockDetails.get(0).getNumber(), is(b0));
        assertThat("block details has incorrect block number",
            blockDetails.get(1).getNumber(), is(b0 + 1));
        assertThat("block details has incorrect block number",
            blockDetails.get(2).getNumber(), is(bn));

        BlockDetails b0Details = blockDetails.get(0);
        assertThat("block details has incorrect number of transactions",
            b0Details.getTxDetails().size(), is(1));
        assertThat("block details' tx details incorrect contract address",
            b0Details.getTxDetails().get(0).getContract(), is(not(nullValue())));
        assertThat("block details' tx details incorrect value",
            b0Details.getTxDetails().get(0).getValue().equals(amount), is(true));

    }

    private BigInteger getNonce() throws InterruptedException {
        RpcResult<BigInteger> nonceResult = this.rpc.getNonce(this.preminedPrivateKey.getAddress());
        assertTrue(nonceResult.isSuccess());
        return nonceResult.getResult();
    }

    private TransactionReceipt sendTransaction(RawTransaction transaction) throws InterruptedException {
        // we want to ensure that the transaction gets sealed into a block.
        IEvent transactionIsSealed = PrepackagedLogEvents.getTransactionSealedEvent(transaction);
        FutureResult<LogEventResult> future = this.listener.listenForEvent(transactionIsSealed, 5, TimeUnit.MINUTES);

        // Send the transaction off.
        System.out.println("Sending the transaction...");
        RpcResult<ReceiptHash> sendResult = this.rpc.sendTransaction(transaction);
        assertTrue(sendResult.isSuccess());

        // Wait on the future to complete and ensure we saw the transaction get sealed.
        System.out.println("Waiting for the transaction to process...");
        LogEventResult listenResult = future.get();
        assertTrue(listenResult.eventWasObserved());
        System.out.println("Transaction was sealed into a block.");

        ReceiptHash hash = sendResult.getResult();

        RpcResult<TransactionReceipt> receiptResult = this.rpc.getTransactionReceipt(hash);
        assertTrue(receiptResult.isSuccess());
        return receiptResult.getResult();
    }

    private static void destroyLogs() throws IOException {
        FileUtils.deleteDirectory(new File(System.getProperty("user.dir") + "/logs"));
    }

    private RawTransaction buildTransactionToCreateAndTransferToFvmContract(BigInteger amount) throws DecoderException, InterruptedException {
        TransactionResult result = RawTransaction.buildAndSignFvmTransaction(
            preminedPrivateKey,
            getNonce(),
            null,
            getFvmContractBytes(),
            ENERGY_LIMIT,
            ENERGY_PRICE,
            amount);

        assertTrue(result.isSuccess());
        return result.getTransaction();
    }

    /**
     * Returns the bytes of an FVM contract named 'PayableConstructor'.
     *
     * See src/org/aion/harness/tests/contracts/fvm/PayableConstructor.sol for the contract itself.
     *
     * We use the bytes directly here, just because we don't have any good utilities in place for
     * generating these bytes ourselves.
     */
    private byte[] getFvmContractBytes() throws DecoderException {
        return Hex.decodeHex("60506040525b5b600a565b6088806100186000396000f300605060405260"
            + "00356c01000000000000000000000000900463ffffffff1680634a6a740714603b578063fc9ad4331"
            + "46043576035565b60006000fd5b60416056565b005b3415604e5760006000fd5b60546059565b005b"
            + "5b565b5b5600a165627a7a723058206bd6d88e9834838232f339ec7235f108a21441649a2cf876547"
            + "229e6c18c098c0029");
    }
}