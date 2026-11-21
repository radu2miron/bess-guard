package edu.tucn.bessguard.contract;

import edu.tucn.bessguard.model.DocType;
import edu.tucn.bessguard.model.SecondLifeBESS;
import edu.tucn.bessguard.util.JsonMapper;
import org.hyperledger.fabric.contract.Context;

/**
 * Persistence and validation of aggregating second-life BESS resources.
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
public class BessService extends AbstractLedgerService<SecondLifeBESS> {
    public BessService() {
        super(SecondLifeBESS.class, DocType.SECOND_LIFE_BESS, SecondLifeBESS::getId, SecondLifeBESS::getDocType);
    }

    public String register(Context ctx, String json) {
        DomainSupport.requireTrustedBackend(ctx);
        SecondLifeBESS bess = JsonMapper.fromJson(json, SecondLifeBESS.class);

        if (bess == null || bess.getDocType() != DocType.SECOND_LIFE_BESS) {
            throw new IllegalArgumentException("Invalid BESS docType.");
        }

        DomainSupport.id(bess.getId(), "id");

        if (bess.getName() == null || bess.getName().isBlank()) {
            throw new IllegalArgumentException("BESS name is required.");
        }

        DomainSupport.optionalText(bess.getDescription(), "description");
        bess.setCreatedAt(DomainSupport.nowText(ctx));
        super.create(ctx, JsonMapper.toJson(bess));
        return JsonMapper.toJson(bess);
    }
}
