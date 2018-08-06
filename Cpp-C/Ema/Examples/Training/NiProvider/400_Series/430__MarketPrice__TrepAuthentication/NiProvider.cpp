///*|-----------------------------------------------------------------------------
// *|            This source code is provided under the Apache 2.0 license      --
// *|  and is provided AS IS with no warranty or guarantee of fit for purpose.  --
// *|                See the project's LICENSE.md for details.                  --
// *|           Copyright Thomson Reuters 2015. All rights reserved.            --
///*|-----------------------------------------------------------------------------

#include "NiProvider.h"
#include <stdlib.h>

using namespace thomsonreuters::ema::access;
using namespace thomsonreuters::ema::rdm;
using namespace thomsonreuters::ema::domain::login;
using namespace std;

const UInt32 maxLength = 256;
EmaString authenticationToken;
EmaString authenticationExtended;
EmaString appId("256");

void printUsage()
{
	cout << "\nOptions:\n"
		<< "  -?                            Shows this usage\n\n"
		<< "  -at <authentication token>    Required Authentication token to use in login request as msgKey.name \n"
		<< "  -aid <applicationId>          ApplicationId set as login Attribute [default = 256]\n"
		<< "  -ax <extended information>    Authentication extended information\n"
		<< "\n";
	exit(-1);
}

void printActiveConfig()
{
	cout << "\nFollowing options are selected:" << endl;

	cout << "appId = " << appId << endl
		<< "authenticationToken = " << authenticationToken << endl
		<< "authenticationextended = " << authenticationExtended << endl;
}

void processCommandLineOptions(int argc, char* argv[])
{
	int iargs = 1;
	authenticationToken.clear();
	authenticationExtended.clear();

	while (iargs < argc)
	{
		if (0 == strcmp("-?", argv[iargs]))
		{
			printUsage();
		}
		else if (strcmp("-at", argv[iargs]) == 0)
		{
			++iargs; if (iargs == argc) printUsage();

			authenticationToken.set(argv[iargs++]);
		}
		else if (strcmp("-aid", argv[iargs]) == 0)
		{
			++iargs; if (iargs == argc) printUsage();

			appId.set(argv[iargs++]);
		}
		else if (strcmp("-ax", argv[iargs]) == 0)
		{
			++iargs; if (iargs == argc) printUsage();

			authenticationExtended.set(argv[iargs++]);
		}
		else
		{
			cout << "Invalid argument: " << argv[iargs] << endl;

			printUsage();
		}

		if (authenticationToken.empty())
		{
			cout << "Missing Authentication Token." << endl;

			printUsage();
		}
	}
}

AppLoginClient::AppLoginClient()
{
	_handle = 0;
	_TTReissue = 0;
}

void AppLoginClient::onRefreshMsg(const RefreshMsg& refreshMsg, const OmmProviderEvent& event)
{
	Login::LoginRefresh tempRefresh;
	cout << endl << "Received login refresh message" << endl;

	_handle = event.getHandle();

	cout << endl << refreshMsg << endl;

	tempRefresh.message(refreshMsg);
	if (tempRefresh.hasAuthenticationTTReissue())
		_TTReissue = tempRefresh.getAuthenticationTTReissue();
	else
		_TTReissue = 0;
}

void AppLoginClient::onUpdateMsg(const UpdateMsg& updateMsg, const OmmProviderEvent&)
{
	cout << endl << updateMsg << endl;
}

void AppLoginClient::onStatusMsg(const StatusMsg& statusMsg, const OmmProviderEvent&)
{
	if (statusMsg.getDomainType() == MMT_LOGIN)
		cout << endl << "Received login status message" << endl;

	cout << endl << statusMsg << endl;
}

int main( int argc, char* argv[] )
{
	try
	{
		authenticationToken.clear();
		authenticationExtended.clear();
		processCommandLineOptions(argc, argv);
		printActiveConfig();
		AppLoginClient loginClient;

		EmaBuffer authnExtendedBuf;

		Login::LoginReq loginMsg;

		OmmNiProviderConfig providerConfig;

		loginMsg.name(authenticationToken).applicationId(appId).nameType(USER_AUTH_TOKEN).role(LOGIN_ROLE_PROV);
		if (!authenticationExtended.empty())
		{
			authnExtendedBuf.setFrom(authenticationExtended.c_str(), authenticationExtended.length());
			loginMsg.authenticationExtended(authnExtendedBuf);
		}


		providerConfig.addAdminMsg(loginMsg.getMessage());

		OmmProvider provider(providerConfig, loginClient);
		UInt64 handle = 6;
		RefreshMsg refresh;
		UpdateMsg update;
		FieldList fieldList;

		provider.submit( refresh.clear().serviceName( "NI_PUB" ).name( "TRI.N" )
			.state( OmmState::OpenEnum, OmmState::OkEnum, OmmState::NoneEnum, "UnSolicited Refresh Completed" )
			.payload( fieldList.clear()
				.addReal( 22, 4100, OmmReal::ExponentNeg2Enum )
				.addReal( 25, 4200, OmmReal::ExponentNeg2Enum )
				.addReal( 30, 20, OmmReal::Exponent0Enum )
				.addReal( 31, 40, OmmReal::Exponent0Enum )
				.complete() )
			.complete(), handle );

		int i = 0;
		while ( i < 60 )
		{
			++i;

			if (loginClient._TTReissue != 0 && getCurrentTime() >= loginClient._TTReissue)
			{
				loginMsg.clear(). name(authenticationToken).applicationId(appId).nameType(USER_AUTH_TOKEN).role(LOGIN_ROLE_PROV);
				if (!authenticationExtended.empty())
				{
					authnExtendedBuf.setFrom(authenticationExtended.c_str(), authenticationExtended.length());
					loginMsg.authenticationExtended(authnExtendedBuf);
				}

				provider.reissue(loginMsg.getMessage(), loginClient._handle);

			}
			provider.submit( update.clear().serviceName("NI_PUB").name("TRI.N")
				.payload( fieldList.clear()
					.addReal( 22, 4100 + i, OmmReal::ExponentNeg2Enum )
					.addReal( 30, 21 + i, OmmReal::Exponent0Enum )
					.complete() ), handle );

			sleep(1000);
		}
	}
	catch ( const OmmException& excp )
	{
		cout << excp << endl;
	}
	return 0;
}
